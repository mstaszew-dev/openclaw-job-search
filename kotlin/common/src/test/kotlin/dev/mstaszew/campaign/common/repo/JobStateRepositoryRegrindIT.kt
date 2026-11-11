package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/**
 * The re-grind selection and guarded claim writes. Real Mongo (replica set) =
 * the update guards are exercised against the actual updateFirst semantics, not
 * an in-memory stand-in.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataMongoTest(properties = ["spring.data.mongodb.auto-index-creation=false"])
class JobStateRepositoryRegrindIT {

    @Autowired
    lateinit var mongo: MongoTemplate

    private lateinit var rgrind: JobStateRepositoryImpl

    @BeforeEach
    fun setup() {
        rgrind = JobStateRepositoryImpl(mongo)
    }

    @BeforeEach
    fun clean() {
        mongo.getCollection("job_state").drop()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mongoUri(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") { MongoReplicaSet.connectionUri }
            registry.add("spring.data.mongodb.database") { "regrind_it" }
        }
    }

    private fun failedRow(
        id: String,
        attempts: Int,
        maxAttempts: Int = 3,
        updatedBefore: Instant = Instant.parse("2026-10-30T00:00:00Z"),
        lastError: String = "loop breaker: browser_click failed 3 consecutive times",
    ) {
        mongo.save(
            JobStateDocument(
                id = id,
                source = id.substringBefore(':'),
                sourceJobId = id.substringAfter(':'),
                companyKey = id.substringAfter(':').substringBefore('-'),
                status = JobStatus.FAILED,
                attempts = attempts,
                maxAttempts = maxAttempts,
                score = 68,
                lastError = lastError,
                updatedAt = updatedBefore,
                createdAt = updatedBefore,
            ),
        )
    }

    @Test
    fun `selects failed rows with attempts left that cooled down, oldest first, capped at the batch`() {
        // distinct updatedAt so the oldest-first sort is deterministic
        failedRow("justjoin:b", attempts = 1, updatedBefore = Instant.parse("2026-10-30T00:00:01Z"))
        failedRow("justjoin:a", attempts = 2, updatedBefore = Instant.parse("2026-10-30T00:00:00Z"))
        failedRow("justjoin:hot", attempts = 1, updatedBefore = Instant.now()) // inside cooldown
        failedRow("justjoin:exhausted", attempts = 3) // attempts == maxAttempts
        failedRow("justjoin:blocked", attempts = 1, lastError = "blocked: portal-block")
        failedRow("justjoin:pending", attempts = 1).also {
            // a PENDING row is not a re-grind candidate
            mongo.updateFirst(
                org.springframework.data.mongodb.core.query.Query.query(
                    org.springframework.data.mongodb.core.query.Criteria.where("_id").`is`("justjoin:pending"),
                ),
                org.springframework.data.mongodb.core.query.Update().set("status", JobStatus.PENDING.name),
                JobStateDocument::class.java,
            )
        }

        val candidates = rgrind.findRegrindCandidates(
            maxAttemptsParam = 3,
            olderThan = Instant.now().minus(java.time.Duration.ofHours(1)),
            strandedAfter = strandedBound(),
            limit = 10,
        )

        assertThat(candidates.map { it.id }).containsExactly("justjoin:a", "justjoin:b")
        assertThat(candidates.first().attempts).isEqualTo(2)
        assertThat(candidates.first().maxAttempts).isEqualTo(3)
        assertThat(candidates.first().score).isEqualTo(68)
    }

    @Test
    fun `respects the batch limit so one window cannot flood the worker`() {
        for (idx in 1..6) failedRow("justjoin:job-0$idx", attempts = 1)

        val candidates = rgrind.findRegrindCandidates(
            maxAttemptsParam = 3,
            olderThan = Instant.now().minus(java.time.Duration.ofHours(1)),
            strandedAfter = strandedBound(),
            limit = 4,
        )

        assertThat(candidates).hasSize(4)
    }

    @Test
    fun `claim flips only the still-FAILED row with attempts left`() {
        failedRow("justjoin:free", attempts = 1)
        failedRow("justjoin:gone", attempts = 3)
        failedRow("justjoin:inflight", attempts = 1)
        val cutoff = Instant.now().minus(java.time.Duration.ofHours(1))
        val strandedAfter = strandedBound()

        assertThat(rgrind.regrindClaim("justjoin:free", maxAttempts = 3, olderThan = cutoff, strandedAfter = strandedAfter)).isTrue()
        mqttAssertStatus("justjoin:free", JobStatus.PENDING)

        // attempts exhausted: claim must refuse and the row must stay FAILED
        assertThat(rgrind.regrindClaim("justjoin:gone", maxAttempts = 3, olderThan = cutoff, strandedAfter = strandedAfter)).isFalse()
        mqttAssertStatus("justjoin:gone", JobStatus.FAILED)

        // already moved on (IN_FLIGHT by the worker, touched inside the
        // cooldown): claim must refuse - freshness is the live-attempt guard
        mongo.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").`is`("justjoin:inflight"),
            ),
            org.springframework.data.mongodb.core.query.Update()
                .set("status", JobStatus.IN_FLIGHT.name)
                .set("updatedAt", Instant.now()),
            JobStateDocument::class.java,
        )
        assertThat(rgrind.regrindClaim("justjoin:inflight", maxAttempts = 3, olderThan = cutoff, strandedAfter = strandedAfter)).isFalse()
        mqttAssertStatus("justjoin:inflight", JobStatus.IN_FLIGHT)
    }

    @Test
    fun `revert moves a still-PENDING claimed row back to FAILED without reopening worker state`() {
        failedRow("justjoin:back", attempts = 1)
        rgrind.regrindClaim("justjoin:back", maxAttempts = 3, olderThan = Instant.now().minus(java.time.Duration.ofHours(1)), strandedAfter = strandedBound())

        assertThat(rgrind.regrindRevert("justjoin:back")).isTrue()
        mqttAssertStatus("justjoin:back", JobStatus.FAILED)
        assertThat(mongo.findById("justjoin:back", JobStateDocument::class.java)!!.attempts).isEqualTo(1)

        // a worker that already resumed the job (PENDING kept its attempts) must not be reverted
        failedRow("justjoin:running", attempts = 1)
        rgrind.regrindClaim("justjoin:running", maxAttempts = 3, olderThan = Instant.now().minus(java.time.Duration.ofHours(1)), strandedAfter = strandedBound())
        mongo.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").`is`("justjoin:running"),
            ),
            org.springframework.data.mongodb.core.query.Update().set("status", JobStatus.IN_FLIGHT.name),
            JobStateDocument::class.java,
        )
        assertThat(rgrind.regrindRevert("justjoin:running")).isFalse()
        mqttAssertStatus("justjoin:running", JobStatus.IN_FLIGHT)
    }

    private fun mqttAssertStatus(id: String, expected: JobStatus) {
        assertThat(mongo.findById(id, JobStateDocument::class.java)!!.status).isEqualTo(expected)
    }

    /**
     * Stranded-IN_FLIGHT bound in tests: comfortably beyond the worst-case
     * live attempt (AgentLoopBounds, 23h with default env), matching how
     * RegrindService derives it in production.
     */
    private fun strandedBound() = Instant.now().minus(java.time.Duration.ofHours(35))

    /**
     * 2026-11-05 specjalista: a model-flake failure exhausted maxAttempts and
     * dead-lettered; the worker resets the attempt counter on such DEAD rows
     * (no employer contact was made - safe to retry), and the regrind pass
     * must recover them once they cool down. DEAD rows that are NOT
     * model-flake (dead gateways, dead portals) stay unrecoverable.
     */
    @Test
    fun `dead model-flake rows are regrind candidates and claimable`() {
        fun deadRow(id: String, lastError: String) {
            failedRow(id, attempts = 0, lastError = lastError)
            mongo.updateFirst(
                org.springframework.data.mongodb.core.query.Query.query(
                    org.springframework.data.mongodb.core.query.Criteria.where("_id").`is`(id),
                ),
                org.springframework.data.mongodb.core.query.Update().set("status", JobStatus.DEAD.name),
                JobStateDocument::class.java,
            )
        }
        deadRow("theprotocol:deadflake", "model-flake failure, requeueing: loop breaker: browser_navigate failed 10 consecutive times")
        deadRow("theprotocol:deadinfra", "llm loop failed: chat failed after 3 attempts")
        deadRow("theprotocol:deadblocked", "blocked: portal-block")

        val candidates = rgrind.findRegrindCandidates(
            maxAttemptsParam = 3,
            olderThan = Instant.now().minus(java.time.Duration.ofHours(1)),
            strandedAfter = strandedBound(),
            limit = 10,
        )

        // Only the dead model-flake row qualifies (no FAILED rows are seeded
        // in this test): containsExactly also catches accidental widening of
        // the FAILED branch (review N3).
        assertThat(candidates.map { it.id }).containsExactly("theprotocol:deadflake")

        assertThat(rgrind.regrindClaim("theprotocol:deadflake", maxAttempts = 3, olderThan = Instant.now().minus(java.time.Duration.ofHours(1)), strandedAfter = strandedBound())).isTrue()
        mqttAssertStatus("theprotocol:deadflake", JobStatus.PENDING)
    }

    /**
     * 2026-11-11: ten rows sat IN_FLIGHT since the Nov 6-7 VM deaths -
     * consumed, marked IN_FLIGHT, never acked, and no pending Kafka record
     * remained (the offset-commit gap of the pre-2026-11-08 consumer).
     * Nothing but this pass can revive that stranding.
     *
     * The line between stranded and racing a live attempt is NOT the regrind
     * cooldown (review B1, 2026-11-11): a live attempt stamps updatedAt once,
     * at attempt start, and can legitimately run to the AgentLoopBounds
     * worst case (23h with default env). The stranded bound must sit beyond
     * that, so a 20h-old IN_FLIGHT row - past any cooldown, still inside a
     * legitimate attempt - must stay untouched.
     */
    @Test
    fun `stranded in-flight rows are regrind candidates and claimable, live-attempt rows are not`() {
        fun inFlightRow(id: String, updated: Instant, attempts: Int = 1) {
            failedRow(id, attempts = attempts)
            mongo.updateFirst(
                org.springframework.data.mongodb.core.query.Query.query(
                    org.springframework.data.mongodb.core.query.Criteria.where("_id").`is`(id),
                ),
                org.springframework.data.mongodb.core.query.Update()
                    .set("status", JobStatus.IN_FLIGHT.name)
                    .set("updatedAt", updated),
                JobStateDocument::class.java,
            )
        }
        val cutoff = Instant.now().minus(java.time.Duration.ofHours(1))
        val strandedAfter = strandedBound()
        inFlightRow("justjoin:stranded", Instant.parse("2026-11-06T12:00:00Z"))
        inFlightRow("justjoin:live-worker", Instant.now())
        // past the cooldown but INSIDE the worst-case attempt window (B1 pin)
        inFlightRow("justjoin:long-attempt", Instant.now().minus(java.time.Duration.ofHours(20)))
        // stranded like the first, but attempts exhausted (review NICE 1)
        inFlightRow("justjoin:exhausted-stranded", Instant.parse("2026-11-06T12:00:00Z"), attempts = 3)

        val candidates = rgrind.findRegrindCandidates(
            maxAttemptsParam = 3,
            olderThan = cutoff,
            strandedAfter = strandedAfter,
            limit = 10,
        )

        assertThat(candidates.map { it.id }).containsExactly("justjoin:stranded")

        assertThat(rgrind.regrindClaim("justjoin:stranded", maxAttempts = 3, olderThan = cutoff, strandedAfter = strandedAfter)).isTrue()
        mqttAssertStatus("justjoin:stranded", JobStatus.PENDING)

        // mid-worst-case attempt: both the query above and the claim must
        // refuse, or the job would be re-enqueued while still being applied
        assertThat(rgrind.regrindClaim("justjoin:long-attempt", maxAttempts = 3, olderThan = cutoff, strandedAfter = strandedAfter)).isFalse()
        mqttAssertStatus("justjoin:long-attempt", JobStatus.IN_FLIGHT)

        assertThat(rgrind.regrindClaim("justjoin:live-worker", maxAttempts = 3, olderThan = cutoff, strandedAfter = strandedAfter)).isFalse()
        mqttAssertStatus("justjoin:live-worker", JobStatus.IN_FLIGHT)
    }
}
