package dev.mstaszew.campaign.common.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/** Ports tracker.json blockers[] rows (captcha, hard portal blocks). */
@Document("blockers")
class BlockerEntity(
    @Id var id: String? = null,
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