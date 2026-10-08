package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.ApplicationEntity
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update

class ApplicationRepositoryImpl(
    private val mongo: MongoTemplate,
) : ApplicationRepositoryCustom {

    override fun findFirstByIdOrUrl(idValue: String, urlValue: String): ApplicationEntity? =
        mongo.findOne(
            Query.query(
                Criteria().orOperator(
                    Criteria.where("_id").`is`(idValue),
                    Criteria.where("url").`is`(urlValue),
                ),
            ).with(SORT_BY_ID),
            ApplicationEntity::class.java,
        )

    override fun findFirstByCompanyKey(companyKey: String): ApplicationEntity? =
        mongo.findOne(
            Query.query(Criteria.where("companyKey").`is`(companyKey)).with(SORT_BY_ID),
            ApplicationEntity::class.java,
        )

    /**
     * $setOnInsert rather than $set: an existing document must not be touched
     * at all, not even its updatedAt. upsertedId is non-null only when this
     * call is the one that created the row.
     */
    override fun insertIfAbsent(application: ApplicationEntity): Boolean {
        val query = Query.query(Criteria.where("_id").`is`(application.id))
        // _id is already pinned by the filter; Mongo rejects it in the update.
        val converted = mongo.getConverter().convertToMongoType(application)
        val update = Update()
        (converted as Map<*, *>)
            .filterKeys { it != "_id" }
            .forEach { (key, value) -> update.setOnInsert(key.toString(), value) }
        val result = mongo.upsert(query, update, ApplicationEntity::class.java)
        return result.upsertedId != null
    }

    private companion object {
        val SORT_BY_ID = Sort.by(Sort.Direction.ASC, "_id")
    }
}