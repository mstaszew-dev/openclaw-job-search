package dev.mstaszew.campaign.finder

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.mstaszew.campaign.common.dedupe.Candidate
import dev.mstaszew.campaign.common.dedupe.CompanyKeyNormalizer
import dev.mstaszew.campaign.common.dedupe.DedupService
import dev.mstaszew.campaign.common.dedupe.UrlNormalizer
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import dev.mstaszew.campaign.common.messaging.JobMessage
import dev.mstaszew.campaign.common.messaging.Topics
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.ChromeLease
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.common.repo.ReenqueueSeed
import dev.mstaszew.campaign.common.repo.SkipRepository
import dev.mstaszew.campaign.finder.collect.CollectedListing
import dev.mstaszew.campaign.finder.collect.ListingCollector
import dev.mstaszew.campaign.finder.policy.EligibilityPolicy
import dev.mstaszew.campaign.finder.policy.EligibilityVerdict
import dev.mstaszew.campaign.finder.policy.IneligibilityReason
import dev.mstaszew.campaign.finder.score.CvScore
import dev.mstaszew.campaign.finder.score.CvScorer
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One discovery run: collect -> upsert -> dedup -> eligibility -> score ->
 * enqueue. Scoring (an external LLM call) runs OUTSIDE any transaction; the
 * persist tail (event, or skip) is one short transaction via TransactionTemplate.
 * Each listing is isolated: one failure is logged and skipped.
 *
 * Delivery is Kafka's job, not ours. This class records a PENDING row in
 * job_state for operator visibility and produces to campaign.jobs; it never
 * reads job_state back to decide what to do, which is what keeps Mongo out of
 * the scheduling path.
 */
@Service
class FinderPipeline(
    private val collector: ListingCollector,
    private val listings: JobListingRepository,
    private val jobState: JobStateRepository,
    private val skips: SkipRepository,
    private val events: EventRepository,
    private val dedup: DedupService,
    private val eligibility: EligibilityPolicy,
    private val scorer: CvScorer,
    private val cvProfile: CvProfile,
    private val tx: TransactionTemplate,
    private val kafka: KafkaTemplate<String, Any>,
    private val mapper: ObjectMapper,
    private val props: FinderProperties,
    private val chromeLease: ChromeLease,
) {

    private val log = LoggerFactory.getLogger(FinderPipeline::class.java)

    // One listing can surface on several filter pages in a single window; two
    // Kafka records for one id each burn an apply attempt downstream. Cleared
    // at the start of every run so the next window decides fresh. A concurrent
    // set because process() is also callable from other threads; correctness
    // of a single window still assumes the scheduler is the only writer.
    private val seenThisWindow: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Instance-unique lease identity: two finder replicas sharing the bare
    // "finder" owner would pass the same-owner refresh branch of the lease,
    // enter a held browser, and each release would drop the other's lease.
    private val leaseOwner = "finder-${UUID.randomUUID().toString().substring(0, 8)}"

    @Scheduled(fixedDelayString = "\${finder.run-interval-seconds:900}000", initialDelayString = "20000")
    fun run() {
        // One lease for the whole pass: all three boards, then release. A
        // live apply holding the Chrome defers the run to the next window
        // rather than interleaving scans with a submission mid-flight.
        val lease = chromeLease.acquire(
            owner = leaseOwner,
            op = "discover",
            ttl = java.time.Duration.ofMinutes(props.leaseTtlMinutes),
            renewEvery = java.time.Duration.ofSeconds(props.leaseRenewSeconds),
        )
        if (lease == null) {
            log.info("browser busy (apply in progress); deferring discovery to the next window")
            return
        }
        try {
            seenThisWindow.clear()
            val summary = WindowSummary()
            for (source in listOf("nofluffjobs", "justjoin", "theprotocol", "pracuj")) {
                val collected = try {
                    collector.collect(source)
                } catch (e: Exception) {
                    log.warn("collector failed for {}: {}", source, e.message)
                    continue
                }
                // an empty batch is a silent collapse mode of its own (a board
                // redesign or selector drift yields 0 items without an error)
                log.info("collected {}: {} listings", source, collected.size)
                for (item in collected) {
                    try {
                        summary.record(process(item))
                    } catch (e: Exception) {
                        log.warn("listing {}:{} failed: {}", item.source, item.sourceJobId, e.message)
                        summary.recordFailure()
                    }
                }
            }
            log.info(summary.line())
        } finally {
            lease.release()
        }
    }

    fun process(item: CollectedListing): Outcome {
        val id = "${item.source}:${item.sourceJobId}"
        if (!seenThisWindow.add(id)) {
            log.info("listing {} was already handled in this window; skipping", id)
            return Outcome.Duplicate(WindowSummary.WINDOW_SCAN_TAG, id)
        }

        val (listing, created) = upsert(item)

        val decision = dedup.check(
            Candidate(
                source = item.source,
                sourceJobId = item.sourceJobId,
                company = listing.company,
                companyKey = listing.companyKey,
                roleTitle = listing.roleTitle,
                url = listing.url,
                region = listing.region,
            ),
        )
        if (decision.duplicate) {
            if (created) {
                // A brand-new listing deduped on first sight is the signature
                // of a mass-block (e.g. a chip-keyed company match): say so
                // loudly, this exact pattern once ran silently for 17 hours.
                log.info(
                    "fresh listing {} deduped on {} ({}); check for a mass-block",
                    id, decision.matchedOn, decision.matchedReference,
                )
            }
            return Outcome.Duplicate(decision.matchedOn?.name, decision.matchedReference)
        }

        val verdict = eligibility.check(listing)
        if (!verdict.eligible) {
            tx.executeWithoutResult {
                recordIneligibilitySkip(listing, verdict)
            }
            return Outcome.Ineligible(verdict.reason?.name ?: "?", verdict.detail)
        }

        // External call: deliberately outside the transaction below.
        val score = scorer.score(
            company = listing.company,
            roleTitle = listing.roleTitle,
            stack = listing.stack,
            salaryText = salaryText(listing),
            applicantCvProfile = cvProfile.profileText(),
        )
        if (score.score < 60) return Outcome.LowScore(score.score, score.reason)

        val message = JobMessage(
            source = listing.source,
            sourceJobId = listing.sourceJobId,
            company = listing.company,
            companyKey = listing.companyKey,
            roleTitle = listing.roleTitle,
            url = listing.url,
            region = listing.region,
            score = score.score,
            scoreReason = score.reason.take(500),
            enqueuedAt = java.time.Instant.now(),
        )

        // Delivery before bookkeeping. A PENDING row whose send failed has no
        // recovery path: the worker never learns about the job, nothing walls
        // the listing, and once it leaves the feed the row strands forever
        // (wielicka, 2026-10-20: six failed sends, five days at PENDING
        // attempts=0). The worker's recordAttempt upsert seeds job_state, so
        // a message without a prior PENDING row is safe, and a failed send
        // retries naturally on the next window.
        try {
            // Keyed by companyKey so every job for one company lands on one
            // partition and is consumed by a single worker, in order.
            kafka.send(Topics.JOBS, message.companyKey, message)
                .get(KAFKA_SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.error("interrupted sending {}; this window's enqueue is dropped for retry", message.id)
            return Outcome.SendFailed(e.message ?: "interrupted")
        } catch (e: Exception) {
            log.error("kafka send failed for {}; the next window re-enqueues it: {}", message.id, e.message)
            return Outcome.SendFailed(e.message ?: "send failed")
        }

        tx.executeWithoutResult {
            // Atomic and guarded (JobStateRepository.markReenqueued): the
            // send already succeeded, so the worker may be consuming right
            // now; a read-modify-save could clobber its terminal status or
            // lose an attempt increment. attempts is preserved by design -
            // the counter resetting to zero on every window is what
            // un-bounded the re-grind loop (iteamly read 1/3 across five
            // grinds).
            jobState.markReenqueued(
                ReenqueueSeed(
                    id = message.id,
                    source = message.source,
                    sourceJobId = message.sourceJobId,
                    companyKey = message.companyKey,
                    score = score.score,
                    scoreReason = message.scoreReason,
                ),
            )
            events.save(
                EventEntity(
                    action = "enqueued",
                    record = record {
                        put("id", message.id)
                        put("company", message.company)
                        put("score", score.score)
                        put("scoreSource", score.source.name)
                    },
                ),
            )
        }
        return Outcome.Enqueued(score.score, score.reason)
    }

    /** Returns the stored listing and whether this run created it. */
    private fun upsert(item: CollectedListing): Pair<JobListingEntity, Boolean> {
        val companyKey = CompanyKeyNormalizer.normalize(item.company)
        val existing = listings.findBySourceAndSourceJobId(item.source, item.sourceJobId)
        if (existing != null) {
            return Pair(listings.save(existing.apply { updateListing(this, item) }), false)
        }
        return Pair(listings.save(item.toEntity(companyKey)), true)
    }

    /** Salary and filter skips are bookkeeping (they never match in dedup). */
    private fun recordIneligibilitySkip(listing: JobListingEntity, verdict: EligibilityVerdict) {
        val reason = when (verdict.reason) {
            IneligibilityReason.SALARY_BELOW_FLOOR -> SkipReason.SALARY
            else -> SkipReason.FILTER
        }
        if (skips.existsByListingIdAndReason(listing.id, reason)) return
        skips.save(
            SkipEntity(
                reason = reason,
                listingId = listing.id,
                source = listing.source,
                sourceJobId = listing.sourceJobId,
                company = listing.company,
                companyKey = listing.companyKey,
                roleTitle = listing.roleTitle,
                url = listing.url,
                region = listing.region,
                remotePolicy = listing.remotePolicy,
                detail = "finder:${verdict.reason}: ${verdict.detail}".take(500),
            ),
        )
    }

    private fun updateListing(target: JobListingEntity, item: CollectedListing) {
        target.roleTitle = item.roleTitle
        target.remotePolicy = item.remotePolicy
        target.salaryMin = item.salaryMin
        target.salaryMax = item.salaryMax
        target.stack = item.stack
        target.raw = item.rawJson?.let(mapper::readTree)
        target.touch()
    }

    private fun salaryText(l: JobListingEntity): String? {
        val min = l.salaryMin ?: return l.salaryMax?.let { "to $it ${l.salaryCurrency ?: "PLN"}" }
        return "$min - ${l.salaryMax ?: "?"} ${l.salaryCurrency ?: "PLN"}"
    }

    private fun CollectedListing.toEntity(companyKey: String) = JobListingEntity(
        source = source,
        sourceJobId = sourceJobId,
        company = company,
        companyKey = companyKey,
        roleTitle = roleTitle,
        roleKey = roleKeyOrNull(roleTitle),
        url = UrlNormalizer.normalize(url),
        region = region,
        remotePolicy = remotePolicy,
        salaryMin = salaryMin,
        salaryMax = salaryMax,
        stack = stack,
        raw = rawJson?.let(mapper::readTree),
    )

    private fun record(build: ObjectNode.() -> Unit): com.fasterxml.jackson.databind.JsonNode =
        mapper.createObjectNode().apply(build)

    private fun roleKeyOrNull(roleTitle: String): String? =
        roleTitle.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').takeIf { it.isNotEmpty() }

    private companion object {
        /** Bounded send await: the enqueue path must fail fast, not hang the window. */
        const val KAFKA_SEND_TIMEOUT_MS = 10_000L
    }

    sealed class Outcome {
        data class Enqueued(val score: Int, val reason: String) : Outcome()
        data class SendFailed(val reason: String?) : Outcome()
        data class Duplicate(val matchedOn: String?, val reference: String?) : Outcome()
        data class Ineligible(val reason: String, val detail: String) : Outcome()
        data class LowScore(val score: Int, val reason: String) : Outcome()
    }
}