package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.dedupe.CompanyKeyNormalizer
import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import dev.mstaszew.campaign.common.domain.BlockerEntity
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import dev.mstaszew.campaign.common.repo.ApplicationRepository
import dev.mstaszew.campaign.common.repo.BlockerRepository
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.common.repo.SkipRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Ports update_tracker.py: the single-transaction recorder. Every recorded
 * outcome writes its application/skip/blocker row, the append-only event, and
 * the job_state bookkeeping atomically.
 *
 * There is no lease guard here any more, and none is needed. The old
 * ownsLease check existed so a reaped task's stale agent could not write; Kafka
 * removes the reap entirely. The guard that replaces it is stronger and
 * cheaper: the application row is written with a single-document $setOnInsert
 * upsert keyed on _id, so a redelivered message for an already-applied job
 * matches the existing document and changes nothing at all.
 */
@Service
class Recorder(
    private val applications: ApplicationRepository,
    private val jobState: JobStateRepository,
    private val skips: SkipRepository,
    private val blockers: BlockerRepository,
    private val events: EventRepository,
    private val listings: JobListingRepository,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(Recorder::class.java)

    data class Evidence(val confirmationUrl: String?, val confirmationText: String?, val ats: String?)

    @Transactional
    fun recordSubmitted(
        listing: JobListingEntity,
        jobId: String,
        evidence: Evidence,
        applyMethod: String = "portal",
    ): Boolean {
        val verdict = SubmissionVerifier.evaluate(evidence.confirmationUrl, evidence.confirmationText)
        val status = if (verdict.valid) ApplicationStatus.SUBMITTED else ApplicationStatus.ATTEMPTED
        val inserted = applications.insertIfAbsent(
            ApplicationEntity(
                id = jobId,
                source = listing.source,
                sourceJobId = listing.sourceJobId,
                company = listing.company,
                companyKey = listing.companyKey,
                roleTitle = listing.roleTitle,
                roleKey = listing.roleKey,
                url = listing.url,
                region = listing.region,
                remotePolicy = listing.remotePolicy,
                stack = listing.stack,
                applyMethod = applyMethod,
                ats = evidence.ats,
                status = status,
                confirmationUrl = evidence.confirmationUrl,
                confirmationText = evidence.confirmationText,
                evidence = mapper.createObjectNode().apply {
                    put("type", "portal_confirmation")
                    put("verdict", verdict.reason)
                    put("valid", verdict.valid)
                },
                appliedAt = Instant.now(),
            ),
        )
        if (!inserted) {
            // Redelivery: the first outcome stands. Writing the event again
            // would double-count in the ledger, so stop here.
            log.info("application {} already recorded; ignoring redelivery", jobId)
            return false
        }
        events.save(event("submitted", listing, mapOf("status" to status.name.lowercase(), "verdict" to verdict.reason)))
        markJobState(
            jobId,
            if (status == ApplicationStatus.SUBMITTED) JobStatus.SUBMITTED else JobStatus.ATTEMPTED,
        )
        return true
    }

    @Transactional
    fun recordSkippedDuplicate(listing: JobListingEntity, jobId: String, detail: String) {
        skips.save(
            SkipEntity(
                reason = SkipReason.DUPLICATE,
                listingId = listing.id,
                source = listing.source,
                sourceJobId = listing.sourceJobId,
                company = listing.company,
                companyKey = listing.companyKey,
                roleTitle = listing.roleTitle,
                url = listing.url,
                detail = detail.take(500),
            ),
        )
        events.save(event("skippedDuplicate", listing, mapOf("detail" to detail)))
        markJobState(jobId, JobStatus.SKIPPED_DUPLICATE)
    }

    /**
     * Repeat-block guard port: a SECOND block for the same company writes a
     * dedupe-visible blockedRepeat skip. Sentinel company keys fall back to
     * LISTING scope (source:sourceJobId) exactly like update_tracker.py, so a
     * twice-blocked confidential listing still stops. Counting uses unresolved
     * blocks only: resolved blocks should not guard forever (documented
     * deviation from the Python all-blocks count).
     */
    @Transactional
    fun recordBlocked(listing: JobListingEntity, jobId: String, reason: String, detail: String) {
        blockers.save(
            BlockerEntity(
                source = listing.source,
                sourceJobId = listing.sourceJobId,
                company = listing.company,
                companyKey = listing.companyKey,
                roleTitle = listing.roleTitle,
                url = listing.url,
                reason = reason,
                detail = detail.take(500),
            ),
        )
        events.save(event("blockedManual", listing, mapOf("reason" to reason, "detail" to detail)))

        val sentinel = listing.companyKey != null && CompanyKeyNormalizer.isSentinelKey(listing.companyKey)
        val priorBlocks = if (sentinel) {
            blockers.countBySourceAndSourceJobIdAndResolvedFalse(listing.source, listing.sourceJobId)
        } else {
            blockers.countByCompanyKeyAndResolvedFalse(listing.companyKey ?: "")
        }
        if (priorBlocks >= 2) {
            val existing = if (sentinel) null
            else skips.findFirstByReasonAndCompanyKeyAndBlockedRepeatTrue(SkipReason.DUPLICATE, listing.companyKey ?: "")
            if (existing != null) {
                existing.blockCount = priorBlocks.toInt()
                existing.detail = "auto blockedRepeat (blockCount=$priorBlocks)"
                skips.save(existing)
            } else {
                skips.save(
                    SkipEntity(
                        reason = SkipReason.DUPLICATE,
                        listingId = listing.id,
                        source = listing.source,
                        sourceJobId = listing.sourceJobId,
                        company = listing.company,
                        companyKey = listing.companyKey,
                        roleTitle = listing.roleTitle,
                        url = listing.url,
                        detail = "auto blockedRepeat (blockCount=$priorBlocks)",
                        blockedRepeat = true,
                        blockCount = priorBlocks.toInt(),
                    ),
                )
            }
        }
        markJobState(jobId, JobStatus.FAILED, "blocked: $reason")
    }

    private fun markJobState(jobId: String, status: JobStatus, lastError: String? = null) {
        val existing = jobState.findById(jobId).orElse(null)
        if (existing == null) {
            // The finder's row can legitimately be absent (imported backlog,
            // manual replay); bookkeeping must never fail the real work.
            val split = jobId.split(':', limit = 2)
            jobState.save(
                JobStateDocument(
                    id = jobId,
                    source = split.getOrElse(0) { "" },
                    sourceJobId = split.getOrElse(1) { jobId },
                    status = status,
                    lastError = lastError,
                ),
            )
            return
        }
        jobState.save(existing.apply {
            this.status = status
            if (lastError != null) this.lastError = lastError
            touch()
        })
    }

    private fun event(action: String, listing: JobListingEntity, fields: Map<String, String>) =
        EventEntity(
            action = action,
            record = mapper.createObjectNode().apply {
                put("id", "${listing.source}:${listing.sourceJobId}")
                put("company", listing.company)
                fields.forEach { (k, v) -> put(k, v) }
            },
        )
}