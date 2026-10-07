package dev.mstaszew.campaign.common.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * Ports tracker.json applications[] rows. Natural id is 'source:sourceJobId',
 * identical to the Python campaign so imported and new rows share one keyspace.
 */
@Entity
@Table(name = "applications")
class ApplicationEntity(
    @Id
    var id: String = "",
    var source: String = "",
    var sourceJobId: String = "",
    var company: String = "",
    var companyKey: String = "",
    var roleTitle: String = "",
    var roleKey: String? = null,
    var url: String? = null,
    var region: String = "PL",
    var remotePolicy: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var salary: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    var stack: List<String> = emptyList(),
    var applyMethod: String? = null,
    var ats: String? = null,
    @Enumerated(EnumType.STRING)
    var status: ApplicationStatus = ApplicationStatus.SUBMITTED,
    var confirmationUrl: String? = null,
    var confirmationText: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var evidence: String? = null,
    var appliedAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var followUps: String? = null,
    var notes: String? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
