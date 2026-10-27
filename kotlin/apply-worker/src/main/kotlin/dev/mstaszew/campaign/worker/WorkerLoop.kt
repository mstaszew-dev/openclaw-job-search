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
import dev.mstaszew.campaign.common.repo.ChromeLease
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.worker.apply.ApplyResult
import dev.mstaszew.campaign.worker.apply.BrowserAgent
import dev.mstaszew.campaign.worker.apply.Recorder
import dev.mstaszew.campaign.worker.apply.SlackNotifier
import dev.mstaszew.campaign.worker.apply.SubmissionVerifier
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
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
 * - max.poll.interval.ms must stay above the worst-case agent loop. Both are
 *   derived from AgentLoopBounds (WORKER_MAX_STEPS, default 80: 80 steps x
 *   (90s model + 120s Playwright) = 4.7h + 30 min margin), so an env override
 *   raises loop and budget together instead of evicting a still-running apply.
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
    private val notifier: SlackNotifier,
    private val chromeLease: ChromeLease,
    private val props: WorkerProperties,
    private val tx: TransactionTemplate,
    private val kafka: KafkaTemplate<String, Any>,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(WorkerLoop::class.java)

    // Instance-unique lease identity: two pods sharing the default worker-id
    // must never pass the same-owner refresh branch of the lease, or a second
    // acquirer would enter a held browser and its release would drop the
    // first holder's lease.
    private val leaseOwner = "worker:${props.workerId}-${UUID.randomUUID().toString().substring(0, 8)}"

    @KafkaListener(
        topics = [Topics.JOBS],
        groupId = Topics.APPLY_GROUP,
        containerFactory = "applyKafkaListenerContainerFactory",
    )
    fun onMessage(job: JobMessage, ack: Acknowledgment) {
        val attempt = recordAttempt(job)
        try {
            // The attempt bound exists to keep the BROWSER closed for a
            // listing that already burned its shots; shadow never opens the
            // browser, so an over-bound delivery there takes the ordinary
            // shadow path. Inside the try so a dedup failure on this path
            // follows the same error policy as any other failure.
            if (attempt > props.maxAttempts && props.applyMode == "live") {
                overBound(job, attempt, ack)
                return
            }
            when (props.applyMode) {
                "live" -> liveApply(job)
                else -> shadowRelease(job)
            }
            ack.acknowledge()
        } catch (e: Exception) {
            val message = e.message?.take(300) ?: e::class.simpleName.orEmpty()
            log.warn("job {} attempt {} failed: {}", job.id, attempt, message)
            if (attempt >= props.maxAttempts) {
                deadLetter(job, attempt, message, ack)
            } else {
                // No commit: the broker redelivers after the backoff below.
                backoff(attempt)
            }
        }
    }

    /**
     * Live mode only (the caller's guard checks applyMode): the counter is
     * per LISTING id and multiple Kafka records can carry one id (finder
     * re-scans a listing on another filter page). Past the bound the browser
     * must not open again - but a redelivery of an already-submitted job
     * (crash between the portal POST and the offset commit) must surface as
     * SKIPPED_DUPLICATE, not DEAD: the dedup read is a Mongo query, not a
     * browser turn, so it stays cheap on the dead path.
     */
    private fun overBound(job: JobMessage, attempt: Int, ack: Acknowledgment) {
        val listing = listingFor(job)
        val decision = dedup.check(candidateFor(job, listing))
        if (decision.duplicate) {
            log.info(
                "live: {} is a duplicate ({}); over-bound redelivery recorded as skipped",
                job.id,
                decision.matchedOn,
            )
            recorder.recordSkippedDuplicate(listing, job.id, "dedupe:${decision.matchedOn}")
            markTerminal(job, JobStatus.SKIPPED_DUPLICATE)
            ack.acknowledge()
            return
        }
        deadLetter(job, attempt, "attempt bound ${props.maxAttempts} already burned for this listing", ack)
    }

    /**
     * Await the dead-letter write before committing. A fire-and-forget send
     * followed by a commit loses the message from BOTH topics if the DLQ
     * broker is briefly unreachable, and job_state would still read DEAD, so
     * the loss would be invisible.
     */
    private fun deadLetter(job: JobMessage, attempt: Int, message: String, ack: Acknowledgment) {
        val deadLettered = try {
            kafka.send(Topics.JOBS_DLQ, job.companyKey, DeadLetter(job, attempt, message, Instant.now()))
                .get(DLQ_SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            null
        } catch (e: InterruptedException) {
            // Shutdown, or the poll-interval watchdog. runCatching would
            // swallow the interrupt flag and leave the consumer running
            // a message it can no longer finish.
            Thread.currentThread().interrupt()
            log.error("interrupted while dead-lettering job {}; leaving the offset uncommitted", job.id)
            return
        } catch (e: Exception) {
            e
        }
        if (deadLettered != null) {
            log.error(
                "job {} exhausted but the dead-letter write failed; leaving the offset uncommitted",
                job.id,
                deadLettered,
            )
            return
        }
        failJob(job, message)
        // Wall the listing from finder re-enqueue before the commit: the
        // dedup wall reads applications + DUPLICATE skips only, and a
        // dead letter writes neither by itself. Without this, one hostile
        // listing was re-enqueued every dedup window and re-ground to the
        // step cap each time (the 2026-10-25 zombie loop: 5 deliveries,
        // ~5h, one iteamly page, browser wedged after each). Live only:
        // shadow's contract is zero ledger writes. Same contract as the
        // DLQ send above: a failed wall write leaves the offset
        // uncommitted, and the redelivery re-walls via overBound.
        val walled = try {
            if (props.applyMode == "live") {
                recorder.recordDeadLettered(
                    listingFor(job),
                    job.id,
                    "dead-letter after $attempt attempts: ${message.take(300)}",
                )
            }
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.error("interrupted while walling job {}; leaving the offset uncommitted", job.id)
            return
        } catch (e: Exception) {
            e
        }
        if (walled != null) {
            log.error(
                "job {} dead-lettered but the wall write failed; leaving the offset uncommitted",
                job.id,
                walled,
            )
            return
        }
        // After the DLQ write and the terminal job_state update: Slack reports
        // persisted facts, not intentions. The notifier itself swallows all
        // failures, so this cannot break the commit that follows.
        notifier.deadLettered(job.id, message)
        ack.acknowledge()
    }

    /**
     * The atomic $inc lives in the repository (see AttemptSeed), not here: it
     * is the only bound on redelivery, so it has to be one atomic upsert rather
     * than a read-modify-save that a rebalance could interleave.
     */
    private fun recordAttempt(job: JobMessage): Int {
        val attempt = jobState.beginAttempt(
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
        // After the increment, never before: a redelivery of an already
        // finished job must still count as an attempt.
        jobState.markInFlight(job.id)
        return attempt
    }

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

        // The lease spans exactly the browser loop, and nothing else queues
        // behind it: dedupe above is a Mongo read and stays outside. Waiting
        // for the browser is bounded (leaseWaitSeconds); past that the job is
        // deferred for redelivery rather than applied without exclusion.
        val lease = browserLease(job.id)
        try {
            applyWithBrowser(job, listing)
        } finally {
            lease.release()
        }
    }

    private fun browserLease(jobId: String): ChromeLease.Handle =
        chromeLease.acquire(
            owner = leaseOwner,
            op = "apply",
            ttl = java.time.Duration.ofMinutes(props.leaseTtlMinutes),
            wait = java.time.Duration.ofSeconds(props.leaseWaitSeconds),
            renewEvery = java.time.Duration.ofSeconds(props.leaseRenewSeconds),
        ) ?: throw BrowserBusyException(jobId)

    private fun applyWithBrowser(job: JobMessage, listing: JobListingEntity) {
        val result = browserAgent.run(
            jobId = job.id,
            listingId = listing.id
                ?: error("listing ${job.id} was never persisted; cannot drive the browser for it"),
            applicantProfileText = System.getenv("APPLICANT_PROFILE_TEXT") ?: "see mounted applicant profile",
            cvPathPl = System.getenv("CV_PATH_PL") ?: "/app/cv/michael-staszewski-cv-pl.pdf",
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
                // Same verdict the Recorder computed, recomputed here because
                // the two must not diverge. Marking this branch SUBMITTED
                // unconditionally wrote SUBMITTED into job_state while the
                // applications row said ATTEMPTED for a submission whose
                // confirmation evidence did not verify.
                val verdict = SubmissionVerifier.evaluate(result.confirmationUrl, result.confirmationText)
                markTerminal(job, if (verdict.valid) JobStatus.SUBMITTED else JobStatus.ATTEMPTED)
                if (verdict.valid) {
                    notifier.submission(listing.company, listing.roleTitle, listing.source)
                } else {
                    notifier.failed(job.id, "submission evidence did not verify")
                }
                log.info("job {}: recorded (valid={})", job.id, verdict.valid)
            }
            is ApplyResult.DuplicateNow -> recorder.recordSkippedDuplicate(listing, job.id, result.detail)
            is ApplyResult.Blocked -> {
                recorder.recordBlocked(listing, job.id, result.reason, result.detail)
                // Review finding 2026-10-26: portal-block is as permanent per
                // listing as captcha (no-SSO login wall, dead form, paywall);
                // the first block is terminal for the listing either way.
                when (result.reason) {
                    "captcha" -> wallBlockedReenqueue(listing, job, "captcha", result.detail)
                    "portal-block" -> wallBlockedReenqueue(listing, job, "portal-block", result.detail)
                }
                notifier.blocked(job.id, result.reason)
            }
            is ApplyResult.Failed -> {
                log.warn("job {} failed: {}", job.id, result.detail)
                markTerminal(job, JobStatus.FAILED, result.detail)
                wallFailedReenqueue(listing, job, result.detail)
                notifier.failed(job.id, result.detail)
            }
        }
    }

    /**
     * The model cannot solve image/hCaptcha-class challenges and a
     * portal-block (no-SSO login, dead form, paywall) recurs on every grind,
     * so a Blocked outcome of either class is terminal for that listing;
     * without this wall the finder re-enqueued it and the worker ground the
     * same doomed form a second time before the repeat-block company guard
     * fired. Best-effort like the failed wall: the grind already happened,
     * a failed wall write logs loudly and keeps the ack.
     */
    private fun wallBlockedReenqueue(listing: JobListingEntity, job: JobMessage, kind: String, detail: String) {
        try {
            when (kind) {
                "captcha" -> recorder.recordCaptchaWall(listing, job.id, "captcha: ${detail.take(300)}")
                else -> recorder.recordPortalBlockWall(listing, job.id, "portal-block: ${detail.take(300)}")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.error("interrupted while {}-walling job {}", kind, job.id)
        } catch (e: Exception) {
            log.error("failed to {}-wall {} from re-enqueue; it may re-grind", kind, job.id, e)
        }
    }

    /**
     * A Failed outcome is terminal for this delivery but must also stop the
     * NEXT one: the finder enqueues anything the dedup wall does not cover,
     * and FAILED used to write no wall, so a hostile listing re-ground the
     * browser on every window it stayed in the feed (iteamly, 2026-10-25:
     * four grinds in one evening, attempts reset by each re-enqueue).
     * Best-effort by design: the grind already happened, so a failed wall
     * write logs loudly and keeps the ack instead of sending the message
     * back to the browser for another grind. The swallow is bounded by the
     * finder's attempts-preserve contract (markReenqueued never resets the
     * counter): past maxAttempts, overBound routes to deadLetter and its
     * strict wall-before-commit policy. Never weaken one side without the
     * other.
     * INFRASTRUCTURE EXCEPTION: failures whose detail carries an
     * infrastructure signature (initializeServer timeout = the wedged
     * browser; process died = the MCP node crash) are OUR outage, not the
     * listing's hostility - walling them burned seven good pracuj jobs in
     * 20 minutes during the 2026-10-27 wedge. No wall: once the browser
     * recovers, the finder re-enqueues them naturally.
     */
    private fun wallFailedReenqueue(listing: JobListingEntity, job: JobMessage, detail: String) {
        if (INFRA_SIGNATURES.any { detail.contains(it) }) {
            log.error(
                "job {} failed with an infrastructure signature ({}); NOT walling - " +
                    "the listing will be re-enqueued after recovery",
                job.id,
                detail.take(120),
            )
            return
        }
        try {
            recorder.recordFailedWall(listing, job.id, "browser-failed: ${detail.take(300)}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.error("interrupted while walling failed job {}", job.id)
        } catch (e: Exception) {
            log.error("failed to wall {} from re-enqueue; it may re-grind", job.id, e)
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

        /**
         * Failure-text fragments that mean OUR infrastructure broke, not the
         * listing: the wedged browser (initializeServer timeout) and the
         * crashed MCP node process. Units failing with these must not wall.
         */
        val INFRA_SIGNATURES = listOf("initializeServer", "process died")
    }

}
