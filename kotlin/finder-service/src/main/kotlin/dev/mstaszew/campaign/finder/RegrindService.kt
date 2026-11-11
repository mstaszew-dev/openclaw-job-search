package dev.mstaszew.campaign.finder

import dev.mstaszew.campaign.common.messaging.AgentLoopBounds
import dev.mstaszew.campaign.common.messaging.JobMessage
import dev.mstaszew.campaign.common.messaging.Topics
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.common.repo.RegrindCandidate
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The second chance pass. The boards' fresh supply is finite: once a window
 * dedupes itself into near-zero enqueues the only candidates left are jobs a
 * previous attempt left FAILED on a transient issue (an old step cap, a loop
 * breaker, a timed-out upload). This pass re-enqueues a small bounded batch of
 * those rows.
 *
 * Ordering is claim-then-send: the atomic FAILED->PENDING claim happens before
 * the Kafka send so two passes can never produce a message for the same row,
 * and the reverted-on-send-failure write keeps the row back in FAILED for the
 * next pass. The seam between claim and send is a stranded-PENDING risk on a
 * hard crash mid-pass, acknowledged here because the alternative (send-then-
 * claim) would race the worker over attempts on every pass.
 *
 * Attempts are never reset here: a row that exhausts its attempts on the road
 * dies to the DLQ exactly as a discovery-enqueued job would.
 */
@Service
class RegrindService(
    private val jobState: JobStateRepository,
    private val listings: JobListingRepository,
    private val kafka: KafkaTemplate<String, Any>,
    private val props: FinderProperties,
) {

    private val log = LoggerFactory.getLogger(RegrindService::class.java)

    // Offset from the discovery schedule by the initial delay: a pass runs
    // while the boards are quiet, never interleaving a Kafka burst with a
    // browser doing anything. No browser lease needed; nothing here touches CDP.
    @Scheduled(
        fixedDelayString = "\${finder.regrind-interval-seconds:900}000",
        initialDelayString = "\${finder.regrind-initial-delay-seconds:180}000",
    )
    fun run() {
        if (!props.regrindEnabled) return
        val now = Instant.now()
        val cutoff = now.minus(Duration.ofHours(props.regrindCooldownHours))
        // B1, 2026-11-11: a live attempt stamps updatedAt once, at attempt
        // start, and can run to AgentLoopBounds.worstCaseMs. The stranded
        // bound sits beyond worst-case plus cooldown so a live job is never
        // re-enqueued mid-flight.
        val strandedAfter = now.minus(Duration.ofMillis(AgentLoopBounds.worstCaseMs).plus(Duration.ofHours(props.regrindCooldownHours)))
        val batch = jobState.findRegrindCandidates(
            maxAttemptsParam = props.regrindMaxAttempts,
            olderThan = cutoff,
            strandedAfter = strandedAfter,
            limit = props.regrindBatchPerWindow,
        )
        if (batch.isEmpty()) return
        log.info("re-grind pass: {} candidate(s) past the {}h cooldown", batch.size, props.regrindCooldownHours)
        var produced = 0
        for (candidate in batch) {
            try {
                if (regrind(candidate, cutoff, strandedAfter)) produced += 1
            } catch (e: Exception) {
                log.warn("re-grind {} failed: {}", candidate.id, e.message)
            }
        }
        if (produced > 0) log.info("re-grind pass: produced {}/{} message(s)", produced, batch.size)
    }

    /** Returns true when a message went out. */
    internal fun regrind(candidate: RegrindCandidate, cutoff: Instant, strandedAfter: Instant): Boolean {
        val listing = listings.findBySourceAndSourceJobId(candidate.source, candidate.sourceJobId)
        if (listing == null) {
            // No stored listing left to rebuild the self-contained payload
            // from; the send would reach the worker with nothing to apply to.
            // Worth a log line: a vanished listing is a data problem, not a
            // quiet skip.
            log.warn("re-grind {}: no stored listing for {}:{}; skipped", candidate.id, candidate.source, candidate.sourceJobId)
            return false
        }
        // Claim before send: a lost claim means another pass or the worker
        // moved the row on between our query and now, and producing anyway
        // would double-bill an attempt.
        if (!jobState.regrindClaim(candidate.id, candidate.maxAttempts, cutoff, strandedAfter)) {
            log.info("re-grind {}: claim lost; another pass or the worker moved it on", candidate.id)
            return false
        }
        val score = candidate.score ?: FALLBACK_SCORE
        val message = JobMessage(
            source = candidate.source,
            sourceJobId = candidate.sourceJobId,
            company = listing.company,
            companyKey = candidate.companyKey,
            roleTitle = listing.roleTitle,
            url = listing.url,
            region = listing.region,
            score = score,
            scoreReason = "finder-regrind: retrying after attempt ${candidate.attempts}/${candidate.maxAttempts}",
            enqueuedAt = Instant.now(),
        )
        try {
            // Same bounded send as FinderPipeline: the pass must fail fast,
            // not wedge the schedule.
            kafka.send(Topics.JOBS, message.companyKey, message)
                .get(KAFKA_SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.error("interrupted sending re-grind {}; reverting claim", message.id)
            jobState.regrindRevert(message.id)
            return false
        } catch (e: Exception) {
            log.error("re-grind send failed for {}; claim reverted so the next pass retries: {}", message.id, e.message)
            jobState.regrindRevert(message.id)
            return false
        }
        return true
    }

    private companion object {
        private const val KAFKA_SEND_TIMEOUT_MS = 10_000L

        /** Neutral score for rows whose original scoring was not recorded. */
        const val FALLBACK_SCORE = 70
    }
}
