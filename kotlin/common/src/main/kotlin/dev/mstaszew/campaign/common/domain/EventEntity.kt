package dev.mstaszew.campaign.common.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Append-only ledger porting events.jsonl; never updated or deleted.
 *
 * The Postgres version ordered reads by a BIGSERIAL `seq`. There is no
 * equivalent here: the generated _id is a UUID string, not an ObjectId, so it
 * carries no time ordering. "Most recent N" therefore sorts on `at` against
 * idx_events_at, and only ties on that are broken by _id.
 */
@Document("events")
class EventEntity(
    @Id var id: String? = null,
    var at: Instant = Instant.now(),
    var action: String = "",
    var record: JsonNode = JsonNodeFactory.instance.objectNode(),
)