package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

class JobStateRepositoryImpl(
    private val mongo: MongoTemplate,
) : JobStateRepositoryCustom {

    override fun beginAttempt(job: AttemptSeed): Int {
        val now = Instant.now()
        // setOnInsert for the descriptive fields, $inc for the counter: a
        // retry must not overwrite the original score or the createdAt stamp,
        // and must not reset the counter it is incrementing.
        mongo.upsert(
            Query.query(Criteria.where("_id").`is`(job.id)),
            Update()
                .setOnInsert("source", job.source)
                .setOnInsert("sourceJobId", job.sourceJobId)
                .setOnInsert("companyKey", job.companyKey)
                .setOnInsert("score", job.score)
                .setOnInsert("scoreReason", job.scoreReason)
                .setOnInsert("createdAt", now)
                .setOnInsert("maxAttempts", job.maxAttempts)
                .set("status", JobStatus.IN_FLIGHT.name)
                .set("updatedAt", now)
                .inc("attempts", 1),
            JobStateDocument::class.java,
        )
        val counter = mongo.findById(job.id, JobStateDocument::class.java)?.attempts
        checkNotNull(counter) { "job_state row for ${job.id} vanished immediately after upsert" }
        return counter
    }
}