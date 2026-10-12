package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository

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
}

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