package dev.mstaszew.campaign.common.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** Ports tracker.json skipped[] rows; reason=duplicate rows participate in dedup. */
@Entity
@Table(name = "skips")
class SkipEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Enumerated(EnumType.STRING)
    var reason: SkipReason = SkipReason.DUPLICATE,
    var listingId: Long? = null,
    var source: String? = null,
    var sourceJobId: String? = null,
    var company: String? = null,
    var companyKey: String? = null,
    var roleTitle: String? = null,
    var roleKey: String? = null,
    var url: String? = null,
    var region: String? = null,
    var remotePolicy: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var salary: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    var stack: List<String> = emptyList(),
    var detail: String? = null,
    var blockedRepeat: Boolean = false,
    var blockCount: Int? = null,
    var at: Instant = Instant.now(),
)
