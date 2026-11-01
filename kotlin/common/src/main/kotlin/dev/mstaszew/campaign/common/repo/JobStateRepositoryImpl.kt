package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

class JobStateRepositoryImpl(
    private val mongo: MongoTemplate,
) : JobStateRepositoryCustom {

    private val log = LoggerFactory.getLogger(JobStateRepositoryImpl::class.java)

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
                .setOnInsert("status", JobStatus.PENDING.name)
                .set("updatedAt", now)
                .inc("attempts", 1),
            JobStateDocument::class.java,
        )
        val counter = mongo.findById(job.id, JobStateDocument::class.java)?.attempts
        checkNotNull(counter) { "job_state row for ${job.id} vanished immediately after upsert" }
        return counter
    }

    /**
     * Moves a job to IN_FLIGHT, unless it already reached a terminal status.
     *
     * Separate from [beginAttempt] because that one must stay a single atomic
     * upsert (the $inc is the only bound on redelivery), and a filter on the
     * same upsert would silently drop the increment whenever the status filter
     * did not match. This runs after the counter is in, so a redelivery after a
     * crash still counts the attempt and only the status needs guarding: a job
     * that already submitted must not be flipped back to IN_FLIGHT and then
     * relabelled SKIPPED_DUPLICATE by the dedupe re-check.
     */
    override fun markInFlight(id: String) {
        val result = mongo.updateFirst(
            Query.query(
                Criteria.where("_id").`is`(id)
                    .and("status").nin(*TERMINAL_STATUSES),
            ),
            Update().set("status", JobStatus.IN_FLIGHT.name).set("updatedAt", Instant.now()),
            JobStateDocument::class.java,
        )
        if (result.matchedCount == 0L) {
            log.debug("job_state {} is already terminal; leaving its status alone", id)
        }
    }

    /**
     * Same split as [beginAttempt]/[markInFlight]: the upsert carries the
     * identity fields and the score refresh, the guarded update moves a
     * non-terminal row to PENDING. The consume may already be running (the
     * finder's send precedes this write), so nothing here may clobber a
     * terminal status or the attempt counter.
     */
    override fun markReenqueued(seed: ReenqueueSeed) {
        val now = Instant.now()
        mongo.upsert(
            Query.query(Criteria.where("_id").`is`(seed.id)),
            Update()
                .setOnInsert("source", seed.source)
                .setOnInsert("sourceJobId", seed.sourceJobId)
                .setOnInsert("companyKey", seed.companyKey)
                .setOnInsert("createdAt", now)
                .setOnInsert("status", JobStatus.PENDING.name)
                .set("score", seed.score)
                .set("scoreReason", seed.scoreReason)
                .set("updatedAt", now),
            JobStateDocument::class.java,
        )
        val result = mongo.updateFirst(
            Query.query(
                Criteria.where("_id").`is`(seed.id)
                    .and("status").nin(*TERMINAL_STATUSES),
            ),
            Update().set("status", JobStatus.PENDING.name).set("updatedAt", Instant.now()),
            JobStateDocument::class.java,
        )
        if (result.matchedCount == 0L) {
            log.debug("job_state {} is already terminal; re-enqueue leaves its status alone", seed.id)
        }
    }

    override fun findRegrindCandidates(
        maxAttemptsParam: Int,
        olderThan: Instant,
        limit: Int,
    ): List<RegrindCandidate> = mongo.find(
        Query.query(
            Criteria.where("status").`is`(JobStatus.FAILED.name)
                .and("attempts").lt(maxAttemptsParam)
                .and("updatedAt").lt(olderThan)
                // "blocked:"-prefixed lastErrors are permanent portal blocks;
                // grinding those again burns an attempt for a known dead end.
                // Negative lookahead (PCRE) so this stays one criteria.
                .and("lastError").regex("""^(?!blocked:)"""),
        ).with(Sort.by(Sort.Direction.ASC, "updatedAt")).limit(limit),
        JobStateDocument::class.java,
    ).map { row ->
        RegrindCandidate(
            id = row.id,
            source = row.source,
            sourceJobId = row.sourceJobId,
            companyKey = row.companyKey,
            score = row.score,
            attempts = row.attempts,
            maxAttempts = row.maxAttempts,
        )
    }

    override fun regrindClaim(id: String, maxAttempts: Int): Boolean {
        val result = mongo.updateFirst(
            Query.query(
                Criteria.where("_id").`is`(id)
                    .and("status").`is`(JobStatus.FAILED.name)
                    .and("attempts").lt(maxAttempts),
            ),
            Update()
                .set("status", JobStatus.PENDING.name)
                .unset("lastError")
                .set("updatedAt", Instant.now()),
            JobStateDocument::class.java,
        )
        return result.matchedCount > 0L
    }

    override fun regrindRevert(id: String): Boolean {
        val result = mongo.updateFirst(
            Query.query(
                Criteria.where("_id").`is`(id)
                    .and("status").`is`(JobStatus.PENDING.name),
            ),
            Update()
                .set("status", JobStatus.FAILED.name)
                .set("lastError", "regrind: kafka send failed; row returned to FAILED")
                .set("updatedAt", Instant.now()),
            JobStateDocument::class.java,
        )
        return result.matchedCount > 0L
    }

    private companion object {
        val TERMINAL_STATUSES = arrayOf(
            JobStatus.SHADOW_RELEASED.name,
            JobStatus.SUBMITTED.name,
            JobStatus.ATTEMPTED.name,
            JobStatus.SKIPPED_DUPLICATE.name,
            JobStatus.SKIPPED_INELIGIBLE.name,
            JobStatus.FAILED.name,
            JobStatus.DEAD.name,
        )
    }
}