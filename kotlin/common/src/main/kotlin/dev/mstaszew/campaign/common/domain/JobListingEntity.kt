package dev.mstaszew.campaign.common.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "job_listings")
class JobListingEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
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
    @JdbcTypeCode(SqlTypes.ARRAY)
    var stack: List<String> = emptyList(),
    /** Canonical JSON string of the raw board payload; parsed at the edges only. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var raw: String? = null,
    var discoveredAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    fun touch() {
        updatedAt = Instant.now()
    }
}
