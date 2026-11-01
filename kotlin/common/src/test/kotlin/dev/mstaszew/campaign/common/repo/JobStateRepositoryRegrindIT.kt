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
            limit = 4,
        )

        assertThat(candidates).hasSize(4)
    }

    @Test
    fun `claim flips only the still-FAILED row with attempts left`() {
        failedRow("justjoin:free", attempts = 1)
        failedRow("justjoin:gone", attempts = 3)
        failedRow("justjoin:inflight", attempts = 1)

        assertThat(rgrind.regrindClaim("justjoin:free", maxAttempts = 3)).isTrue()
        mqttAssertStatus("justjoin:free", JobStatus.PENDING)

        // attempts exhausted: claim must refuse and the row must stay FAILED
        assertThat(rgrind.regrindClaim("justjoin:gone", maxAttempts = 3)).isFalse()
        mqttAssertStatus("justjoin:gone", JobStatus.FAILED)

        // already moved on (IN_FLIGHT by the worker): claim must refuse
        mongo.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").`is`("justjoin:inflight"),
            ),
            org.springframework.data.mongodb.core.query.Update().set("status", JobStatus.IN_FLIGHT.name),
            JobStateDocument::class.java,
        )
        assertThat(rgrind.regrindClaim("justjoin:inflight", maxAttempts = 3)).isFalse()
        mqttAssertStatus("justjoin:inflight", JobStatus.IN_FLIGHT)
    }

    @Test
    fun `revert moves a still-PENDING claimed row back to FAILED without reopening worker state`() {
        failedRow("justjoin:back", attempts = 1)
        rgrind.regrindClaim("justjoin:back", maxAttempts = 3)

        assertThat(rgrind.regrindRevert("justjoin:back")).isTrue()
        mqttAssertStatus("justjoin:back", JobStatus.FAILED)
        assertThat(mongo.findById("justjoin:back", JobStateDocument::class.java)!!.attempts).isEqualTo(1)

        // a worker that already resumed the job (PENDING kept its attempts) must not be reverted
        failedRow("justjoin:running", attempts = 1)
        rgrind.regrindClaim("justjoin:running", maxAttempts = 3)
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
}
