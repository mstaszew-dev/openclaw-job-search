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
     * The dead-letter wall: the finder re-enqueues anything the dedup wall
     * does not cover, and a dead letter without a skip row re-grinds the
     * worker to the step cap on every dedup window (the 2026-10-25 zombie
     * loop: 5 deliveries, ~5h, one iteamly page). Deliberately
     * companyKey-less - the wall must stop THIS listing (the id and url
     * branches), not silence every other open requisition of the company
     * via the dedup company branch. Leaves job_state alone: deadLetter
     * already marked it DEAD, and a SKIPPED_DUPLICATE overwrite would
     * corrupt the dead count in stats.
     */
    @Transactional
    fun recordDeadLettered(listing: JobListingEntity, jobId: String, detail: String) {
        wallListing("deadLettered", listing, detail)
    }

    /**
     * The browser-failed wall: the same shape as the dead-letter wall. A
     * Failed outcome is terminal in job_state but used to write nothing the
     * dedup wall reads, so the finder re-enqueued the listing on every window
     * it stayed in the feed and the worker re-ground it (2026-10-25 iteamly
     * zombie, the FAILED twin of the dead-letter loop walled above).
     * Deliberately companyKey-less for the same reason: the wall stops THIS
     * listing, not the company's other open requisitions.
     */
    @Transactional
    fun recordFailedWall(listing: JobListingEntity, jobId: String, detail: String) {
        wallListing("failedWall", listing, detail)
    }

    /**
     * The captcha wall: the FIRST captcha block walls the listing itself,
     * because the model cannot solve image/hCaptcha-class challenges and a
     * re-enqueue of the same listing just grinds the same doomed form again
     * (margo 2026-10-26: two full grinds before the repeat-block company
     * wall fired). Same listing-scoped shape as the other walls; job_state
     * stays FAILED (recordBlocked already marked it).
     */
    @Transactional
    fun recordCaptchaWall(listing: JobListingEntity, jobId: String, detail: String) {
        wallListing("captchaWall", listing, detail)
    }

    /**
     * The portal-block wall: same rationale as the captcha wall - a
     * portal-blocked listing (login required without SSO, dead ATS form,
     * paywall) is blocked for the SAME reasons on every future grind, so
     * the first block is terminal for the listing. Distinct event action so
     * ops can tell the classes apart.
     */
    @Transactional
    fun recordPortalBlockWall(listing: JobListingEntity, jobId: String, detail: String) {
        wallListing("portalBlockWall", listing, detail)
    }

    /**
     * Shared write shape of both re-enqueue walls (dead-letter and
     * browser-failed): a DUPLICATE skip that stops THIS listing via the id
     * and url branches - never companyKey, which would wall the company's
     * other requisitions - plus an audit event. job_state is left alone.
     */
    private fun wallListing(action: String, listing: JobListingEntity, detail: String) {
        skips.save(
            SkipEntity(
                reason = SkipReason.DUPLICATE,
                listingId = listing.id,
                source = listing.source,
                sourceJobId = listing.sourceJobId,
                company = listing.company,
                roleTitle = listing.roleTitle,
                url = listing.url,
                detail = detail.take(500),
            ),
        )
        events.save(event(action, listing, mapOf("detail" to detail)))
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