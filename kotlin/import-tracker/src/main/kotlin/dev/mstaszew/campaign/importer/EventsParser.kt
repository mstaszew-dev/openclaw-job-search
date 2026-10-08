package dev.mstaszew.campaign.importer

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.domain.EventEntity
import java.time.Instant
import java.time.OffsetDateTime

/** Parses the append-only events.jsonl ledger into event rows. */
class EventsParser(private val mapper: ObjectMapper) {

    fun parse(jsonl: String): List<EventEntity> =
        jsonl.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val n = mapper.readTree(line)
                val record = n.path("record")
                EventEntity(
                    at = n.path("at").asText(null)?.let(::parseInstant) ?: Instant.now(),
                    action = n.path("action").asText("unknown"),
                    // The live ledger has record-less lines. BSON stores an
                    // empty subdocument fine, so unlike jsonb there is nothing
                    // to reject and no NUL escape to scrub: a BSON string may
                    // contain U+0000, which is why the old sanitize step for
                    // jsonb is gone rather than merely relocated.
                    record = if (record.isMissingNode || record.isNull) mapper.createObjectNode() else record,
                )
            }
            .toList()

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }
        .recoverCatching { OffsetDateTime.parse(text).toInstant() }
        .getOrNull()
}
