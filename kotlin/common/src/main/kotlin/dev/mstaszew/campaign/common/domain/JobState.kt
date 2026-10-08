package dev.mstaszew.campaign.common.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Bookkeeping for one Kafka message's journey. NOT a queue.
 *
 * Kafka decides what work exists and who gets it; this collection records what
 * happened after the fact. It is written but never read to decide delivery,
 * which is the whole point of the migration: there is no claim, no lease, no
 * heartbeat and no reaper, so nothing here can strand a job.
 */
@Document("job_state")
class JobStateDocument(
    /** 'source:sourceJobId', the same key applications uses. */
    @Id var id: String = "",
    var source: String = "",
    var sourceJobId: String = "",
    var companyKey: String = "",
    var status: JobStatus = JobStatus.PENDING,
    var attempts: Int = 0,
    var maxAttempts: Int = 3,
    var score: Int? = null,
    var scoreReason: String? = null,
    var lastError: String? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    fun touch() {
        updatedAt = Instant.now()
    }
}

enum class JobStatus {
    PENDING,
    IN_FLIGHT,

    /** Shadow mode: consumed, deduped, recorded, no browser apply. */
    SHADOW_RELEASED,
    SUBMITTED,
    ATTEMPTED,
    SKIPPED_DUPLICATE,
    SKIPPED_INELIGIBLE,
    FAILED,

    /** Attempts exhausted; the message went to campaign.jobs.dlq. */
    DEAD,
    ;

    val isTerminal: Boolean
        get() = this != PENDING && this != IN_FLIGHT
}