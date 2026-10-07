package dev.mstaszew.campaign.common.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Ports tracker.json blockers[] rows (captcha, hard portal blocks). */
@Entity
@Table(name = "blockers")
class BlockerEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var source: String? = null,
    var sourceJobId: String? = null,
    var company: String? = null,
    var companyKey: String? = null,
    var roleTitle: String? = null,
    var url: String? = null,
    var reason: String = "",
    var resolved: Boolean = false,
    var detail: String? = null,
    var at: Instant = Instant.now(),
)
