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
                EventEntity(
                    at = n.path("at").asText(null)?.let(::parseInstant) ?: Instant.now(),
                    action = n.path("action").asText("unknown"),
                    record = n.path("record").toString(),
                )
            }
            .toList()

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }
        .recoverCatching { OffsetDateTime.parse(text).toInstant() }
        .getOrNull()
}
