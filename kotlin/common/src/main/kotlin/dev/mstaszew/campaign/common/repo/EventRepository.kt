package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.EventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface EventRepository : JpaRepository<EventEntity, Long> {

    /** Max imported events.jsonl line number, for idempotent re-imports. */
    @Query("select max(coalesce(e.seq, 0)) from EventEntity e")
    fun maxSeq(): Long?
}
