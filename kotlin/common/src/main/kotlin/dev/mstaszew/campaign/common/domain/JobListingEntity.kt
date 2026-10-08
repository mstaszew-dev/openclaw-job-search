package dev.mstaszew.campaign.common.domain

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant

/**
 * Discovered board listings. The id is a Mongo ObjectId hex string, assigned on
 * insert; it is null until then, which is why every consumer that copies it
 * (SkipEntity.listingId) takes a String?.
 */
@Document("job_listings")
class JobListingEntity(
    @Id var id: String? = null,
    var source: String = "",
    var sourceJobId: String = "",
    var company: String = "",
    var companyKey: String = "",
    var roleTitle: String = "",
    var roleKey: String? = null,
    var url: String = "",
    var region: String = "PL",
    var remotePolicy: String? = null,
    var salaryMin: BigDecimal? = null,
    var salaryMax: BigDecimal? = null,
    var salaryCurrency: String? = null,
    var salaryBasis: String? = null,
    var stack: List<String> = emptyList(),
    /** Raw board payload as a real subdocument; parsed at the edges only. */
    var raw: JsonNode? = null,
    var discoveredAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    fun touch() {
        updatedAt = Instant.now()
    }
}