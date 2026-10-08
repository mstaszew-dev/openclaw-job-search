package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * attempts is the only bound on how long Kafka may redeliver a job: it is what
 * gates the dead-letter decision. A lost increment there means a poison
 * message retries forever, so the counter has to survive concurrent callers.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataMongoTest(properties = ["spring.data.mongodb.auto-index-creation=false"])
class JobStateRepositoryIT {

    @Autowired
    lateinit var jobState: JobStateRepository

    @Autowired
    lateinit var mongo: org.springframework.data.mongodb.core.MongoTemplate

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mongoUri(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") { MongoReplicaSet.connectionUri }
            registry.add("spring.data.mongodb.database") { "job_state_repository_it" }
        }
    }

    @BeforeEach
    fun clean() {
        jobState.deleteAll()
    }

    private fun seed(id: String = "justjoin:1", maxAttempts: Int = 3) = AttemptSeed(
        id = id,
        source = "justjoin",
        sourceJobId = "1",
        companyKey = "acme",
        score = 80,
        scoreReason = "kotlin",
        maxAttempts = maxAttempts,
    )

    @Test
    fun `first attempt creates the row and counts one`() {
        assertThat(jobState.beginAttempt(seed())).isEqualTo(1)
        val doc = jobState.findById("justjoin:1").orElseThrow()
        assertThat(doc.status).isEqualTo(JobStatus.IN_FLIGHT)
        assertThat(doc.source).isEqualTo("justjoin")
        assertThat(doc.score).isEqualTo(80)
    }

    @Test
    fun `retries increment without resetting the original fields`() {
        jobState.beginAttempt(seed())
        assertThat(jobState.beginAttempt(seed())).isEqualTo(2)
        assertThat(jobState.beginAttempt(seed())).isEqualTo(3)

        val doc = jobState.findById("justjoin:1").orElseThrow()
        assertThat(doc.attempts).isEqualTo(3)
        // A later attempt must not restate the score: the first read is the
        // real one, and the finder may have scored differently since.
        assertThat(doc.score).isEqualTo(80)
        assertThat(doc.scoreReason).isEqualTo("kotlin")
    }

    @Test
    fun `concurrent increments do not lose a count`() {
        val threads = 8
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val results = (1..threads).map {
                pool.submit<Int> {
                    start.await()
                    jobState.beginAttempt(seed())
                }
            }.map { it.get() }
            assertThat(results).allMatch { it in 1..threads }
            // The load-modify-save version this replaced lost increments here
            // and left the counter below the true number of attempts.
            assertThat(jobState.findById("justjoin:1").orElseThrow().attempts).isEqualTo(threads)
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }
}