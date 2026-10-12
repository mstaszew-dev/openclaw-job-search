package dev.mstaszew.campaign.common.domain

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Ports tracker.json applications[] rows. Natural id is 'source:sourceJobId',
 * identical to the Python campaign so imported and new rows share one keyspace.
 *
 * The id doubles as the double-apply guard. Kafka is at-least-once, so a
 * redelivered message re-runs the dedupe check and finds this row; the unique
 * index on _id is the hard backstop underneath that.
 */
@Document("applications")
class ApplicationEntity(
    @Id var id: String = "",
    var source: String = "",
    var sourceJobId: String = "",
    var company: String = "",
    var companyKey: String = "",
    var roleTitle: String = "",
    var roleKey: String? = null,
    var url: String? = null,
    var region: String = "PL",
    var remotePolicy: String? = null,
    /**
     * Real BSON subdocuments, not unparsed strings. The tracker stores
     * salarySeen as either a bare string or an object, so the field is typed
     * as JsonNode rather than Map<String, Any?> to keep both shapes storable
     * and both queryable as 'salary.min'.
     */
    var salary: JsonNode? = null,
    var stack: List<String> = emptyList(),
    var applyMethod: String? = null,
    var ats: String? = null,
    var status: ApplicationStatus = ApplicationStatus.SUBMITTED,
    var confirmationUrl: String? = null,
    var confirmationText: String? = null,
    var evidence: JsonNode? = null,
    var appliedAt: Instant? = null,
    var followUps: JsonNode? = null,
    var notes: String? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    fun touch() {
        updatedAt = Instant.now()
    }
}