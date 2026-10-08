package dev.mstaszew.campaign.common.messaging

import java.time.Instant

/**
 * The campaign.jobs payload. Self-contained on purpose: the worker must be able
 * to act on a redelivered message even if the listing document has since been
 * updated or removed.
 *
 * Serialized by the producer with the same ObjectMapper the rest of the app
 * uses, so the Kafka JSON shape is stable and readable in topic dumps.
 */
data class JobMessage(
    val source: String,
    val sourceJobId: String,
    val company: String,
    val companyKey: String,
    val roleTitle: String,
    val url: String?,
    val region: String,
    val score: Int,
    val scoreReason: String,
    val enqueuedAt: Instant,
) {
    /** Matches the applications._id keyspace, which is what dedupe reads. */
    val id: String get() = "$source:$sourceJobId"
}

/** campaign.jobs.dlq payload: why a message gave up. */
data class DeadLetter(
    val job: JobMessage,
    val attempts: Int,
    val lastError: String,
    val failedAt: Instant,
)

object Topics {
    const val JOBS = "campaign.jobs"
    const val JOBS_DLQ = "campaign.jobs.dlq"

    /** Consumer group shared by every apply-worker replica. */
    const val APPLY_GROUP = "apply-workers"
}