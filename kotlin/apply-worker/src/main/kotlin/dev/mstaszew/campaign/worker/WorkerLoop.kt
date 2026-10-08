package dev.mstaszew.campaign.worker

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.dedupe.Candidate
import dev.mstaszew.campaign.common.dedupe.DedupService
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.common.messaging.DeadLetter
import dev.mstaszew.campaign.common.messaging.JobMessage
import dev.mstaszew.campaign.common.messaging.Topics
import dev.mstaszew.campaign.common.repo.AttemptSeed
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.worker.apply.ApplyResult
import dev.mstaszew.campaign.worker.apply.BrowserAgent
import dev.mstaszew.campaign.worker.apply.Recorder
import dev.mstaszew.campaign.worker.apply.SubmissionVerifier
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The apply consumer. Kafka hands us one campaign.jobs message at a time and
 * this class decides what happens to it.
 *
 * - shadow: re-check dedupe, record the outcome, release. No browser, no
 *   application rows: the whole pipeline is exercised against the imported
 *   tracker with zero duplicate-apply risk.
 * - live: BrowserAgent drives the shared Chrome over CDP; the Recorder writes
 *   the application/skip/blocker row.
 *
 * Delivery semantics, which are the whole reason the queue moved off Postgres:
 *
 * - The offset is committed ONLY after the Mongo writes have succeeded, so a
 *   crash in between redelivers rather than losing the job.
 * - Redelivery is safe by construction. Dedupe runs again on every consume,
 *   and Recorder.recordSubmitted writes with insert-if-absent, so the same
 *   message consumed twice produces exactly one application row.
 * - Failure is handled by NOT committing and returning, letting the broker
 *   redeliver. job_state.attempts bounds how long that can go on; once the
 *   bound is hit the message goes to campaign.jobs.dlq and we DO commit.
 * - max.poll.interval.ms (45 min) must stay above the worst-case agent loop
 *   (25 steps x 90s ~= 37 min) or a long apply gets the consumer evicted and
 *   redelivered while it is still running.
 */
@Component
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerLoop(
    private val listings: JobListingRepository,
    private val events: EventRepository,
    private val jobState: JobStateRepository,
    private val dedup: DedupService,
    private val recorder: Recorder,
    private val browserAgent: BrowserAgent,
    private val props: WorkerProperties,
    private val tx: TransactionTemplate,
    private val kafka: KafkaTemplate<String, Any>,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(WorkerLoop::class.java)

    @KafkaListener(
        topics = [Topics.JOBS],
        groupId = Topics.APPLY_GROUP,
        containerFactory = "applyKafkaListenerContainerFactory",
    )
    fun onMessage(job: JobMessage, ack: Acknowledgment) {
        val attempt = recordAttempt(job)
        try {
            when (props.applyMode) {
                "live" -> liveApply(job)
                else -> shadowRelease(job)
            }
            ack.acknowledge()
        } catch (e: Exception) {
            val message = e.message?.take(300) ?: e::class.simpleName.orEmpty()
            log.warn("job {} attempt {} failed: {}", job.id, attempt, message)
            if (attempt >= props.maxAttempts) {
                // Await the dead-letter write before committing. A fire-and-forget
                // send followed by a commit loses the message from BOTH topics
                // if the DLQ broker is briefly unreachable, and job_state would
                // still read DEAD, so the loss would be invisible.
                val deadLettered = runCatching {
                    kafka.send(Topics.JOBS_DLQ, job.companyKey, DeadLetter(job, attempt, message, Instant.now()))
                        .get(DLQ_SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                if (deadLettered.isFailure) {
                    log.error(
                        "job {} exhausted but the dead-letter write failed; leaving the offset uncommitted",
                        job.id,
                        deadLettered.exceptionOrNull(),
                    )
                    return
                }
                failJob(job, message)
                ack.acknowledge()
            } else {
                // No commit: the broker redelivers after the backoff below.
                backoff(attempt)
            }
        }
    }

    /**
     * The atomic $inc lives in the repository (see AttemptSeed), not here: it
     * is the only bound on redelivery, so it has to be one atomic upsert rather
     * than a read-modify-save that a rebalance could interleave.
     */
    private fun recordAttempt(job: JobMessage): Int =
        jobState.beginAttempt(
            AttemptSeed(
                id = job.id,
                source = job.source,
                sourceJobId = job.sourceJobId,
                companyKey = job.companyKey,
                score = job.score,
                scoreReason = job.scoreReason,
                maxAttempts = props.maxAttempts,
            ),
        )

    private fun failJob(job: JobMessage, message: String) {
        tx.executeWithoutResult {
            val existing = jobState.findById(job.id).orElse(null)
            jobState.save(
                (existing ?: JobStateDocument(id = job.id, source = job.source, sourceJobId = job.sourceJobId))
                    .apply {
                        status = JobStatus.DEAD
                        lastError = message
                        maxAttempts = props.maxAttempts
                        touch()
                    },
            )
        }
    }

    private fun backoff(attempt: Int) {
        val millis = BACKOFF_BASE_MS * (1L shl (attempt - 1).coerceIn(0, 6))
        log.info("retrying job in {} ms (attempt {}/{})", millis, attempt, props.maxAttempts)
        Thread.sleep(millis)
    }

    /** Persisted in one short transaction; nothing here schedules work. */
    fun shadowRelease(job: JobMessage) {
        val listing = listingFor(job)
        val decision = dedup.check(candidateFor(job, listing))
        tx.executeWithoutResult {
            events.save(
                EventEntity(
                    action = "shadow_released",
                    record = mapper.createObjectNode().apply {
                        put("id", job.id)
                        put("duplicate", decision.duplicate)
                        put("matchedOn", decision.matchedOn?.name ?: "none")
                    },
                ),
            )
            val existing = jobState.findById(job.id).orElse(null)
            jobState.save(
                (existing ?: JobStateDocument(id = job.id, source = job.source, sourceJobId = job.sourceJobId))
                    .apply {
                        status = JobStatus.SHADOW_RELEASED
                        touch()
                    },
            )
        }
        if (decision.duplicate) log.info("shadow: job {} is a duplicate ({})", job.id, decision.matchedOn)
    }

    /**
     * The message carries everything needed for dedupe and the ledger, but the
     * browser agent and the Recorder still want the listing document. A missing
     * listing is fatal for live mode (nothing to apply to) and irrelevant for
     * shadow, so fall back to a projection built from the message itself.
     */
    private fun listingFor(job: JobMessage): JobListingEntity {
        listings.findBySourceAndSourceJobId(job.source, job.sourceJobId)?.let { return it }
        log.warn("listing for {} is absent; using the message payload", job.id)
        return JobListingEntity(
            source = job.source,
            sourceJobId = job.sourceJobId,
            company = job.company,
            companyKey = job.companyKey,
            roleTitle = job.roleTitle,
            url = job.url ?: "",
            region = job.region,
        )
    }

    private fun candidateFor(job: JobMessage, listing: JobListingEntity) = Candidate(
        source = job.source,
        sourceJobId = job.sourceJobId,
        company = listing.company,
        companyKey = listing.companyKey,
        roleTitle = listing.roleTitle,
        url = listing.url,
        region = listing.region,
    )

    private fun liveApply(job: JobMessage) {
        val listing = listingFor(job)

        // Dedupe runs BEFORE the browser, not after. recordSubmitted only
        // writes the applications row, and that row is written once the form
        // has already gone to a real employer, so insertIfAbsent protects the
        // bookkeeping and not the action. If the worker dies between the
        // submission and the offset commit, the broker redelivers; without
        // this gate the redelivery would submit a second time and then
        // silently decline to record it.
        val decision = dedup.check(candidateFor(job, listing))
        if (decision.duplicate) {
            log.info("live: {} is a duplicate ({}); not opening the browser", job.id, decision.matchedOn)
            recorder.recordSkippedDuplicate(listing, job.id, "dedupe:${decision.matchedOn}")
            markTerminal(job, JobStatus.SKIPPED_DUPLICATE)
            return
        }

        val result = browserAgent.run(
            jobId = job.id,
            listingId = listing.id
                ?: error("listing ${job.id} was never persisted; cannot drive the browser for it"),
            applicantProfileText = System.getenv("APPLICANT_PROFILE_TEXT") ?: "see mounted applicant profile",
            cvPathPl = System.getenv("CV_PATH_PL") ?: "/cv/michael-staszewski-cv-pl.pdf",
            mcpFactory = {
                McpStdioClient(
                    (System.getenv("WORKER_MCP_COMMAND")
                        ?: "node @playwright/mcp/cli.js --cdp-endpoint http://127.0.0.1:9222")
                        .split(' '),
                )
            },
        )
        when (result) {
            is ApplyResult.Submitted -> {
                markTerminal(job, JobStatus.SUBMITTED)
                log.info(
                    "job {}: recorded (valid={})",
                    job.id,
                    SubmissionVerifier.evaluate(result.confirmationUrl, result.confirmationText).valid,
                )
            }
            is ApplyResult.DuplicateNow -> recorder.recordSkippedDuplicate(listing, job.id, result.detail)
            is ApplyResult.Blocked -> recorder.recordBlocked(listing, job.id, result.reason, result.detail)
            is ApplyResult.Failed -> {
                log.warn("job {} failed: {}", job.id, result.detail)
                markTerminal(job, JobStatus.FAILED, result.detail)
            }
        }
    }

    private fun markTerminal(job: JobMessage, status: JobStatus, error: String? = null) {
        tx.executeWithoutResult {
            val existing = jobState.findById(job.id).orElse(null)
            if (existing == null) {
                // recordAttempt upserts before anything else runs, so a missing
                // row here means the counter write was rolled back. Recreating it
                // is better than returning quietly: silently dropping the
                // outcome leaves the ledger and job_state disagreeing with no
                // trace of which one is wrong.
                log.warn("job_state row for {} disappeared; recreating it as {}", job.id, status)
            }
            jobState.save(
                (existing ?: JobStateDocument(
                    id = job.id,
                    source = job.source,
                    sourceJobId = job.sourceJobId,
                    attempts = 1,
                )).apply {
                    this.status = status
                    if (error != null) lastError = error
                    touch()
                },
            )
        }
    }

    private companion object {
        const val BACKOFF_BASE_MS = 2_000L
        const val DLQ_SEND_TIMEOUT_MS = 10_000L
    }

}
