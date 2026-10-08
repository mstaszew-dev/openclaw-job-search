package dev.mstaszew.campaign.common.domain

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Ports tracker.json skipped[] rows; reason=duplicate rows participate in dedup.
 *
 * listingId points at job_listings._id, which is an ObjectId hex string rather
 * than the Postgres BIGSERIAL it replaced, so this is a String?.
 */
@Document("skips")
class SkipEntity(
    @Id var id: String? = null,
    var reason: SkipReason = SkipReason.DUPLICATE,
    var listingId: String? = null,
    var source: String? = null,
    var sourceJobId: String? = null,
    var company: String? = null,
    var companyKey: String? = null,
    var roleTitle: String? = null,
    var roleKey: String? = null,
    var url: String? = null,
    var region: String? = null,
    var remotePolicy: String? = null,
    var salary: JsonNode? = null,
    var stack: List<String> = emptyList(),
    var detail: String? = null,
    var blockedRepeat: Boolean = false,
    var blockCount: Int? = null,
    var at: Instant = Instant.now(),
)