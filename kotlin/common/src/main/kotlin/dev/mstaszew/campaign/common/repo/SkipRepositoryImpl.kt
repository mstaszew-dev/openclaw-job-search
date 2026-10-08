package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

class SkipRepositoryImpl(
    private val mongo: MongoTemplate,
) : SkipRepositoryCustom {

    override fun findFirstByReasonAndCompanyKey(reason: SkipReason, companyKey: String): SkipEntity? =
        first(Criteria.where("reason").`is`(reason.name).and("companyKey").`is`(companyKey))

    override fun findFirstByReasonAndUrl(reason: SkipReason, url: String): SkipEntity? =
        first(Criteria.where("reason").`is`(reason.name).and("url").`is`(url))

    override fun findFirstByReasonAndCompanyKeyAndBlockedRepeatTrue(
        reason: SkipReason,
        companyKey: String,
    ): SkipEntity? =
        first(
            Criteria.where("reason").`is`(reason.name)
                .and("companyKey").`is`(companyKey)
                .and("blockedRepeat").`is`(true),
        )

    override fun findFirstByReasonAndSourceAndSourceJobId(
        reason: SkipReason,
        source: String,
        sourceJobId: String,
    ): SkipEntity? =
        first(
            Criteria.where("reason").`is`(reason.name)
                .and("source").`is`(source)
                .and("sourceJobId").`is`(sourceJobId),
        )

    private fun first(criteria: Criteria): SkipEntity? =
        mongo.findOne(Query.query(criteria).with(SORT_BY_ID), SkipEntity::class.java)

    private companion object {
        val SORT_BY_ID = Sort.by(Sort.Direction.ASC, "_id")
    }
}