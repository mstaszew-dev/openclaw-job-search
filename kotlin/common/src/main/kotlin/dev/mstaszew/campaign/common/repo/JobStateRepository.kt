package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

/**
 * Bookkeeping only. Nothing in the delivery path reads this to decide what to
 * work on next; it exists so operators can see attempts, outcome and DLQ depth.
 */
interface JobStateRepository :
    MongoRepository<JobStateDocument, String>,
    JobStateRepositoryCustom {

    fun countByStatus(status: JobStatus): Long

    /** Sorted by updatedAt: the ledger is "what happened lately", not a heap. */
    fun findByStatusOrderByUpdatedAtDesc(status: JobStatus, page: Pageable): Page<JobStateDocument>
}

interface JobStateRepositoryCustom {

    /**
     * Atomically bumps the attempt counter, creating the row on first sight,
     * and returns the new count. Status is not touched.
     *
     * An atomic $inc rather than read-increment-save on purpose. A rebalance
     * can briefly have the outgoing and incoming partition owner both running
     * this for the same job, and a load-modify-save loses one of those
     * increments. Since attempts is the only bound on redelivery (it gates the
     * dead-letter decision), a lost increment means a poison message loops
     * forever instead of reaching the DLQ.
     */
    fun beginAttempt(job: AttemptSeed): Int

    /**
     * Sets IN_FLIGHT unless the job is already terminal. Call after
     * [beginAttempt], once the counter is safely in place.
     */
    fun markInFlight(id: String)

    /**
     * The finder's re-enqueue write: refreshes the score fields and moves the
     * row to PENDING, guarded so a row the worker already drove to a terminal
     * status is never reopened (a stranded PENDING row is the wielicka
     * failure mode). Creates the row on first sight. Never touches attempts:
     * resetting the counter on re-enqueue is what un-bounded the re-grind
     * loops.
     */
    fun markReenqueued(seed: ReenqueueSeed)

    /**
     * Rows for the periodic re-grind: FAILED on a transient issue (the worker
     * keeps portal blocks under a "blocked:" lastError prefix, which are
     * permanent and excluded) with attempts still inside [maxAttemptsParam],
     * including FAILED rows that carry no lastError field at all; DEAD rows
     * whose death was a model flake; and IN_FLIGHT rows stranded with no
     * pending Kafka record behind them.
     *
     * Two freshness bounds, not one: [olderThan] is the cooldown applied to
     * FAILED and DEAD rows (they are never mid-attempt, so a short quiet
     * window is enough), while [strandedAfter] is the staleness bound for
     * IN_FLIGHT rows. A live attempt stamps updatedAt once, at attempt start,
     * and can legitimately run to the AgentLoopBounds worst case, so
     * [strandedAfter] must sit beyond worst-case-attempt plus cooldown or a
     * live job gets re-enqueued mid-flight. Oldest first, capped at [limit].
     */
    fun findRegrindCandidates(
        maxAttemptsParam: Int,
        olderThan: Instant,
        strandedAfter: Instant,
        limit: Int,
    ): List<RegrindCandidate>

    /**
     * Atomically reclaims one stranded row to PENDING: only if the row is
     * still in a re-grindable branch (FAILED, a cooled model-flake DEAD row,
     * or an IN_FLIGHT row stranded past [strandedAfter] with no live attempt
     * refreshing it) and still has attempts left. [olderThan] is the cooldown
     * guard for FAILED and DEAD rows; [strandedAfter] guards IN_FLIGHT rows
     * so a live attempt cannot be claimed mid-flight. Returns false when
     * another pass won the row, the worker moved it on, or it was touched
     * inside the guard; the caller must not produce a Kafka message for a
     * lost claim.
     */
    fun regrindClaim(id: String, maxAttempts: Int, olderThan: Instant, strandedAfter: Instant): Boolean

    /**
     * Reverts a claimed row whose Kafka send failed. Guarded to still-PENDING
     * rows with attempts left: a claim the worker already resumed is left
     * alone.
     */
    fun regrindRevert(id: String): Boolean
}

/** The finder's enqueue projection of a job; carries no attempt semantics. */
data class ReenqueueSeed(
    val id: String,
    val source: String,
    val sourceJobId: String,
    val companyKey: String,
    val score: Int,
    val scoreReason: String?,
)

/**
 * One row eligible for a re-grind: FAILED on a transient issue with attempts
 * left. The presentation fields (company, title, url) are NOT here on purpose
 * - they are joined from job_listings at send time, so the message always
 * reflects the stored listing rather than a stale projection.
 */
data class RegrindCandidate(
    val id: String,
    val source: String,
    val sourceJobId: String,
    val companyKey: String,
    val score: Int?,
    val attempts: Int,
    val maxAttempts: Int,
)

/** The fields written on first insert; later attempts only bump the counter. */
data class AttemptSeed(
    val id: String,
    val source: String,
    val sourceJobId: String,
    val companyKey: String,
    val score: Int,
    val scoreReason: String?,
    val maxAttempts: Int,
)