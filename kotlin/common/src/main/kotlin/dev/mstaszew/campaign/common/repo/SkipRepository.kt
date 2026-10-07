package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import org.springframework.data.jpa.repository.JpaRepository

interface SkipRepository : JpaRepository<SkipEntity, Long> {

    /** Duplicate skips (incl. blockedRepeat rows) are prior company contacts. */
    fun findFirstByReasonAndCompanyKey(reason: SkipReason, companyKey: String): SkipEntity?

    fun findFirstByReasonAndUrl(reason: SkipReason, url: String): SkipEntity?

    fun findFirstByReasonAndSourceAndSourceJobId(
        reason: SkipReason,
        source: String,
        sourceJobId: String,
    ): SkipEntity?

    fun countByReason(reason: SkipReason): Long
}
