package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import org.springframework.data.mongodb.repository.MongoRepository

interface SkipRepository :
    MongoRepository<SkipEntity, String>,
    SkipRepositoryCustom {

    /** Finder re-runs must not record the same ineligibility skip twice. */
    fun existsByListingIdAndReason(listingId: String?, reason: SkipReason): Boolean

    fun countByReason(reason: SkipReason): Long
}

/**
 * Hand-written for the same reason as ApplicationRepositoryCustom: a derived
 * "first" match in Mongo has no guaranteed order, and these results are quoted
 * into the ledger as the dedupe reference.
 */
interface SkipRepositoryCustom {

    /** Duplicate skips (incl. blockedRepeat rows) are prior company contacts. */
    fun findFirstByReasonAndCompanyKey(reason: SkipReason, companyKey: String): SkipEntity?

    fun findFirstByReasonAndUrl(reason: SkipReason, url: String): SkipEntity?

    /** Existing auto-guard row for a company (updated in place on later blocks). */
    fun findFirstByReasonAndCompanyKeyAndBlockedRepeatTrue(
        reason: SkipReason,
        companyKey: String,
    ): SkipEntity?

    fun findFirstByReasonAndSourceAndSourceJobId(
        reason: SkipReason,
        source: String,
        sourceJobId: String,
    ): SkipEntity?
}