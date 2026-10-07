package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.dedupe.CompanyKeyNormalizer
import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import dev.mstaszew.campaign.common.domain.BlockerEntity
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import dev.mstaszew.campaign.common.domain.TaskState
import dev.mstaszew.campaign.common.repo.ApplicationRepository
import dev.mstaszew.campaign.common.repo.ApplyTaskRepository
import dev.mstaszew.campaign.common.repo.BlockerRepository
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.SkipRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Ports update_tracker.py: the single-transaction recorder. Every recorded
 * outcome writes its application/skip/blocker row, the append-only event, and
 * the task-state change atomically. All writes are guarded by lease ownership
 * (state=CLAIMED, claimed_by=this worker, lease not expired): a stale agent
 * whose task was reaped and re-claimed can no longer write.
 */
@Service
class Recorder(
    private val applications: ApplicationRepository,
    private val tasks: ApplyTaskRepository,
    private val skips: SkipRepository,
    private val blockers: BlockerRepository,
    private val events: EventRepository,
    private val listings: JobListingRepository,
    private val mapper: ObjectMapper,
    @org.springframework.beans.factory.annotation.Value("\${worker.worker-id:worker-1}")
    private val workerId: String,
) {

    private val log = LoggerFactory.getLogger(Recorder::class.java)

    data class Evidence(val confirmationUrl: String?, val confirmationText: String?, val ats: String?)

    /** True when the caller still owns the claim on this task. */
    private fun ownsLease(taskId: Long): Boolean =
        tasks.findById(taskId)
            .map { it.claimedBy == workerId && (it.claimExpiresAt == null || it.claimExpiresAt!!.isAfter(Instant.now())) }
            .orElse(false)

    @Transactional
    fun recordSubmitted(
        listing: JobListingEntity,
        taskId: Long,
        evidence: Evidence,
        applyMethod: String = "portal",
    ): Boolean {
        if (!ownsLease(taskId)) {
            log.warn("task {}: lease lost, dropping submitted record", taskId)
            return false
        }
        val verdict = SubmissionVerifier.evaluate(evidence.confirmationUrl, evidence.confirmationText)
        val status = if (verdict.valid) ApplicationStatus.SUBMITTED else ApplicationStatus.ATTEMPTED
        applications.save(
            ApplicationEntity(
                id = "${listing.source}:${listing.sourceJobId}",
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
                evidence = mapper.writeValueAsString(
                    mapper.createObjectNode().apply {
                        put("type", "portal_confirmation")
                        put("verdict", verdict.reason)
                        put("valid", verdict.valid)
                    },
                ),
                appliedAt = Instant.now(),
            ),
        )
        events.save(event("submitted", listing, mapOf("status" to status.name.lowercase(), "verdict" to verdict.reason)))
        tasks.findById(taskId).ifPresent {
            it.transitionTo(if (status == ApplicationStatus.SUBMITTED) TaskState.SUBMITTED else TaskState.ATTEMPTED)
            tasks.save(it)
        }
        return true
    }

    @Transactional
    fun recordSkippedDuplicate(listing: JobListingEntity, taskId: Long, detail: String) {
        if (!ownsLease(taskId)) {
            log.warn("task {}: lease lost, dropping duplicate skip", taskId)
            return
        }
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
        tasks.findById(taskId).ifPresent {
            it.transitionTo(TaskState.SKIPPED_DUPLICATE)
            tasks.save(it)
        }
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
    fun recordBlocked(listing: JobListingEntity, taskId: Long, reason: String, detail: String) {
        if (!ownsLease(taskId)) {
            log.warn("task {}: lease lost, dropping blocker", taskId)
            return
        }
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
        tasks.findById(taskId).ifPresent {
            it.transitionTo(TaskState.BLOCKED)
            tasks.save(it)
        }
    }

    private fun event(action: String, listing: JobListingEntity, fields: Map<String, String>) =
        EventEntity(
            action = action,
            record = mapper.writeValueAsString(
                mapper.createObjectNode().apply {
                    put("id", "${listing.source}:${listing.sourceJobId}")
                    put("company", listing.company)
                    fields.forEach { (k, v) -> put(k, v) }
                },
            ),
        )
}
