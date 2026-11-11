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
        strandedAfter: Instant,
        limit: Int,
    ): List<RegrindCandidate> {
        // FAILED rows: the classic regrind, minus permanent "blocked:" walls.
        // A FAILED row is never mid-attempt, so the cooldown bound is enough.
        val failedBranch = Criteria().andOperator(
            Criteria.where("status").`is`(JobStatus.FAILED.name),
            Criteria.where("attempts").lt(maxAttemptsParam),
            // "blocked:"-prefixed lastErrors are permanent portal blocks;
            // grinding those again burns an attempt for a known dead end.
            // `$not` with a regex, not a negative-lookahead regex: Mongo
            // regex operators never match a missing field, so 8 rows left
            // FAILED with no lastError at all by the Nov 6 00:19 bulk
            // failure were invisible to this pass forever (2026-11-11).
            // `$not` matches absent fields and still excludes blocked rows.
            Criteria.where("lastError").not().regex("""^blocked:"""),
            Criteria.where("updatedAt").lt(olderThan),
        )
        // DEAD rows whose death was a MODEL flake (2026-11-05 specjalista:
        // navigate-grind exhausted maxAttempts and dead-lettered). The worker
        // resets their attempt counter on dead-letter, so once the cooldown
        // passes the regrind pass gives the listing another roll; DEAD rows
        // from real infrastructure/portal death stay unrecoverable, and the
        // recovery itself is bounded to MAX_REGRIND_CYCLES per listing
        // (review S3: otherwise a deterministically-failed listing cycles
        // forever).
        val deadModelFlakeBranch = Criteria().andOperator(
            Criteria.where("status").`is`(JobStatus.DEAD.name),
            Criteria.where("attempts").lt(maxAttemptsParam),
            Criteria.where("lastError").regex("""^model-flake failure"""),
            Criteria.where("regrindCycles").lt(MAX_REGRIND_CYCLES),
            Criteria.where("updatedAt").lt(olderThan),
        )
        // 2026-11-11 stranding evidence: ten rows sat IN_FLIGHT since the
        // Nov 6-7 VM deaths - consumed, marked IN_FLIGHT, never acked, with
        // no pending Kafka record left behind (the pre-2026-11-08 consumer
        // lost the seek-back and a later offset commit skipped past them).
        // An IN_FLIGHT row CAN be mid-attempt: a live attempt stamps updatedAt
        // once, at attempt start, and can legitimately run to the
        // AgentLoopBounds worst case (23h default). So the stranded bound
        // must sit beyond worst-case plus cooldown, or a live job gets
        // re-enqueued mid-flight (review B1, 2026-11-11). The ten Nov 6-7
        // rows are 5 days stale, so they clear even a 35h bound.
        val strandedInFlightBranch = Criteria().andOperator(
            Criteria.where("status").`is`(JobStatus.IN_FLIGHT.name),
            Criteria.where("attempts").lt(maxAttemptsParam),
            Criteria.where("updatedAt").lt(strandedAfter),
        )
        return mongo.find(
            Query.query(
                Criteria().orOperator(failedBranch, deadModelFlakeBranch, strandedInFlightBranch),
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
    }

    override fun regrindClaim(id: String, maxAttempts: Int, olderThan: Instant, strandedAfter: Instant): Boolean {
        // Defense in depth (review S1, 2026-11-05): the claim mirrors the
        // candidate query's branch conditions, so a future caller (or an ops
        // script) cannot resurrect a permanently dead portal or a
        // non-recoverable DEAD row through this gate. Each branch carries its
        // own freshness bound: FAILED and DEAD use the cooldown
        // [olderThan]; IN_FLIGHT uses [strandedAfter] so a live attempt cannot
        // be claimed mid-flight. beginAttempt and markInFlight both stamp
        // updatedAt, so an IN_FLIGHT row touched inside its bound is a live
        // attempt, not a stranded one.
        val failedBranch = Criteria().andOperator(
            Criteria.where("status").`is`(JobStatus.FAILED.name),
            // Same `$not` guard as the candidate query: a FAILED row with no
            // lastError field must stay claimable, blocked rows must not.
            Criteria.where("lastError").not().regex("""^blocked:"""),
        )
        val deadBranch = Criteria().andOperator(
            Criteria.where("status").`is`(JobStatus.DEAD.name),
            Criteria.where("lastError").regex("""^model-flake failure"""),
            Criteria.where("regrindCycles").lt(MAX_REGRIND_CYCLES),
        )
        val strandedInFlightBranch = Criteria().andOperator(
            Criteria.where("status").`is`(JobStatus.IN_FLIGHT.name),
        )
        val result = mongo.updateFirst(
            Query.query(
                Criteria.where("_id").`is`(id)
                    .and("attempts").lt(maxAttempts)
                    .orOperator(
                        Criteria().andOperator(failedBranch, Criteria.where("updatedAt").lt(olderThan)),
                        Criteria().andOperator(deadBranch, Criteria.where("updatedAt").lt(olderThan)),
                        Criteria().andOperator(strandedInFlightBranch, Criteria.where("updatedAt").lt(strandedAfter)),
                    ),
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

    companion object {
        /**
         * Recovery bound (review S3, 2026-11-05): a listing the model
         * deterministically fails gets at most this many dead-letter ->
         * regrind cycles; after that its DEAD row stays terminal.
         */
        const val MAX_REGRIND_CYCLES = 2

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