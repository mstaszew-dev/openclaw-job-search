package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import org.springframework.data.jpa.repository.JpaRepository

interface SkipRepository : JpaRepository<SkipEntity, Long> {

    /** Duplicate skips (incl. blockedRepeat rows) are prior company contacts. */
    fun findFirstByReasonAndCompanyKey(reason: SkipReason, companyKey: String): SkipEntity?

    fun findFirstByReasonAndUrl(reason: SkipReason, url: String): SkipEntity?

    /** Existing auto-guard row for a company (updated in place on later blocks). */
    fun findFirstByReasonAndCompanyKeyAndBlockedRepeatTrue(reason: SkipReason, companyKey: String): SkipEntity?

    fun findFirstByReasonAndSourceAndSourceJobId(
        reason: SkipReason,
        source: String,
        sourceJobId: String,
    ): SkipEntity?

    fun countByReason(reason: SkipReason): Long
}
