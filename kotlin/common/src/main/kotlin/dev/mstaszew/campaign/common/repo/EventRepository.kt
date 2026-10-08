package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.EventEntity
import org.springframework.data.mongodb.repository.MongoRepository

/**
 * The ledger is append-only. The Postgres JPQL maxSeq() counter is gone with
 * the table it counted: idempotent event re-imports are gated on
 * countDocuments({}) == 0 instead.
 */
interface EventRepository : MongoRepository<EventEntity, String> {

    /**
     * Most recent first, ordered by `at` rather than _id. Spring Data generates
     * a UUID string for EventEntity._id, which carries no time ordering, so
     * ordering by _id would return an arbitrary slice of the ledger.
     */
    fun findTop20ByOrderByAtDesc(): List<EventEntity>
}