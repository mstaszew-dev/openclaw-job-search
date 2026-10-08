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
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
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
) {

    private val log = LoggerFactory.getLogger(FinderPipeline::class.java)

    @Scheduled(fixedDelayString = "\${finder.run-interval-seconds:900}000", initialDelayString = "20000")
    fun run() {
        for (source in listOf("nofluffjobs", "justjoin", "theprotocol")) {
            val collected = try {
                collector.collect(source)
            } catch (e: Exception) {
                log.warn("collector failed for {}: {}", source, e.message)
                continue
            }
            for (item in collected) {
                try {
                    process(item)
                } catch (e: Exception) {
                    log.warn("listing {}:{} failed: {}", item.source, item.sourceJobId, e.message)
                }
            }
        }
    }

    fun process(item: CollectedListing): Outcome {
        val listing = upsert(item)

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
        if (decision.duplicate) return Outcome.Duplicate(decision.matchedOn?.name, decision.matchedReference)

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

        tx.executeWithoutResult {
            jobState.save(
                JobStateDocument(
                    id = message.id,
                    source = message.source,
                    sourceJobId = message.sourceJobId,
                    companyKey = message.companyKey,
                    status = JobStatus.PENDING,
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

        // Keyed by companyKey so every job for one company lands on one
        // partition and is consumed by a single worker, in order.
        kafka.send(Topics.JOBS, message.companyKey, message).whenComplete { _, ex ->
            if (ex != null) log.warn("kafka send failed for {}: {}", message.id, ex.message)
        }
        return Outcome.Enqueued(score.score, score.reason)
    }

    private fun upsert(item: CollectedListing): JobListingEntity {
        val companyKey = CompanyKeyNormalizer.normalize(item.company)
        val existing = listings.findBySourceAndSourceJobId(item.source, item.sourceJobId)
        if (existing != null) {
            return listings.save(existing.apply { updateListing(this, item) })
        }
        return listings.save(item.toEntity(companyKey))
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

    sealed class Outcome {
        data class Enqueued(val score: Int, val reason: String) : Outcome()
        data class Duplicate(val matchedOn: String?, val reference: String?) : Outcome()
        data class Ineligible(val reason: String, val detail: String) : Outcome()
        data class LowScore(val score: Int, val reason: String) : Outcome()
    }
}