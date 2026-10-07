package dev.mstaszew.campaign.api

import dev.mstaszew.campaign.common.repo.ApplyTaskRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class QueueItemDto(
    val id: Long,
    val state: String,
    val source: String?,
    val sourceJobId: String?,
    val company: String?,
    val roleTitle: String?,
    val priority: Int,
    val attempts: Int,
    val maxAttempts: Int,
    val score: Int?,
    val scoreReason: String?,
    val claimedBy: String?,
    val claimExpiresAt: Instant?,
    val createdAt: Instant,
)

data class RequeueResponse(val id: Long, val state: String)

/** Queue inspection; writes live in QueueService. */
@RestController
@RequestMapping("/api/v1/queue")
class QueueController(
    private val tasks: ApplyTaskRepository,
    private val listings: JobListingRepository,
    private val queueService: QueueService,
) {

    @GetMapping
    fun queue(
        @PageableDefault(size = 50, sort = ["id"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): Page<QueueItemDto> {
        val page = tasks.findAll(pageable)
        // one batched query for the page's listings (no N+1)
        val byId = listings.findAllById(page.map { it.listingId }).associateBy { it.id }
        return page.map { toDto(it, byId[it.listingId]) }
    }

    @GetMapping("/{id}")
    fun task(@PathVariable id: Long): ResponseEntity<QueueItemDto> =
        tasks.findById(id)
            .map { ResponseEntity.ok(toDto(it, listings.findById(it.listingId).orElse(null))) }
            .orElse(ResponseEntity.notFound().build())

    @PostMapping("/{id}/requeue")
    fun requeue(@PathVariable id: Long): ResponseEntity<RequeueResponse> =
        ResponseEntity.ok(queueService.requeue(id))

    private fun toDto(
        t: dev.mstaszew.campaign.common.domain.ApplyTaskEntity,
        listing: dev.mstaszew.campaign.common.domain.JobListingEntity?,
    ) = QueueItemDto(
        id = t.id,
        state = t.state.name,
        source = listing?.source,
        sourceJobId = listing?.sourceJobId,
        company = listing?.company,
        roleTitle = listing?.roleTitle,
        priority = t.priority,
        attempts = t.attempts,
        maxAttempts = t.maxAttempts,
        score = t.score,
        scoreReason = t.scoreReason,
        claimedBy = t.claimedBy,
        claimExpiresAt = t.claimExpiresAt,
        createdAt = t.createdAt,
    )
}
