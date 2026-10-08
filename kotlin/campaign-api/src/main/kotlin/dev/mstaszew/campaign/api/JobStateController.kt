package dev.mstaszew.campaign.api

import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.repo.JobStateRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The operator view of what the pipeline did with each message.
 *
 * This is NOT a queue view and must not be built into one: job_state is
 * bookkeeping written after the fact, while Kafka owns delivery. The old
 * GET /queue with its requeue endpoint was deleted with apply_tasks; what
 * replaces it answers "what happened to the jobs I saw" not "what is due".
 * Restarting or redelivering is a Kafka topic/offset operation, not an API.
 */
@RestController
class JobStateController(private val jobState: JobStateRepository) {

    @GetMapping("/api/v1/jobs")
    fun jobs(
        @RequestParam("status") status: String?,
        @RequestParam("limit") limit: Int?,
    ): Map<String, Any> {
        val limitValue = (limit ?: 50).coerceIn(1, 500)
        val req = PageRequest.of(0, limitValue, Sort.by(Sort.Direction.DESC, "updatedAt"))
        val docs: List<JobStateDocument> = if (status.isNullOrBlank()) {
            jobState.findAll(req).content
        } else {
            val parsed = JobStateStatusParser.parse(status)
            jobState.findByStatusOrderByUpdatedAtDesc(parsed, req).content
        }
        return mapOf(
            "count" to docs.size,
            "jobs" to docs.map { it.asJobView() },
        )
    }

    private fun JobStateDocument.asJobView(): Map<String, Any?> = mapOf(
        "id" to id,
        "status" to status.name,
        "attempts" to attempts,
        "maxAttempts" to maxAttempts,
        "score" to score,
        "lastError" to lastError,
        "updatedAt" to updatedAt.toString(),
    )
}

private object JobStateStatusParser {
    fun parse(raw: String): dev.mstaszew.campaign.common.domain.JobStatus =
        runCatching { dev.mstaszew.campaign.common.domain.JobStatus.valueOf(raw.uppercase()) }.getOrElse {
            throw IllegalArgumentException(
                "unknown status '$raw'; valid: ${dev.mstaszew.campaign.common.domain.JobStatus.entries.joinToString()}",
            )
        }
}
