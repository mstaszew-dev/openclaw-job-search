package dev.mstaszew.campaign.common.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One unit of apply work. Claimed exclusively via SKIP LOCKED (see
 * ApplyTaskRepository.claim); the lease guards against dead workers.
 */
@Entity
@Table(name = "apply_tasks")
class ApplyTaskEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var listingId: Long = 0,
    @Enumerated(EnumType.STRING)
    var state: TaskState = TaskState.DISCOVERED,
    var priority: Int = 0,
    var attempts: Int = 0,
    var maxAttempts: Int = 3,
    var claimedBy: String? = null,
    var claimExpiresAt: Instant? = null,
    var score: Int? = null,
    var scoreReason: String? = null,
    var detail: String? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var stateChangedAt: Instant = Instant.now(),
) {
    fun transitionTo(next: TaskState) {
        state = next
        stateChangedAt = Instant.now()
        updatedAt = stateChangedAt
        if (!next.isActive) {
            claimedBy = null
            claimExpiresAt = null
        }
    }
}
