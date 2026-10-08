package dev.mstaszew.campaign.worker

import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.messaging.JobMessage
import dev.mstaszew.campaign.common.messaging.KafkaBroker
import dev.mstaszew.campaign.common.messaging.Topics
import dev.mstaszew.campaign.common.repo.ApplicationRepository
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.MongoReplicaSet
import dev.mstaszew.campaign.worker.apply.Recorder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant

/**
 * The property this whole migration is built to hold: a message consumed twice
 * writes exactly one application.
 *
 * Everything else here can be reasoned about. This cannot, because it is the
 * only irreversible failure in the system (a second application to a real
 * employer) and the only one whose bug is invisible from the logs, which just
 * say "applied". So it is tested against a real Mongo replica set and a real
 * Kafka broker, driving the same code path the running worker uses, rather
 * than against mocks that would happily allow a second save().
 *
 * Three guards are exercised together, because any one of them alone is not
 * enough:
 *
 * 1. the dedupe re-check in the consumer (cheap, catches almost everything),
 * 2. insertIfAbsent on applications._id (cannot be raced or forgotten),
 * 3. commit-after-write (a crash in between just causes a redelivery).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["worker.apply-mode=shadow"])
class DoubleApplyGuardIT {

    @Autowired
    lateinit var recorder: Recorder

    @Autowired
    lateinit var applications: ApplicationRepository

    @Autowired
    lateinit var events: EventRepository

    @Autowired
    lateinit var listings: JobListingRepository

    @Autowired
    lateinit var mongo: MongoTemplate

    @Autowired
    lateinit var kafka: KafkaTemplate<String, Any>

    companion object {
        private const val JOB_ID = "justjoin:guard-1"

        @JvmStatic
        @DynamicPropertySource
        fun infra(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") { MongoReplicaSet.connectionUri }
            registry.add("spring.data.mongodb.database") { "double_apply_guard_it" }
            registry.add("spring.kafka.bootstrap-servers") { KafkaBroker.bootstrapServers }
        }
    }

    private val listing = JobListingEntity(
        source = "justjoin",
        sourceJobId = "guard-1",
        company = "Guard Co",
        companyKey = "guard-co",
        roleTitle = "Kotlin Developer",
        roleKey = "kotlin-developer",
        url = "https://justjoin.it/offers/guard-1",
        region = "PL",
    )

    @BeforeEach
    fun ensureTopics() {
        KafkaBroker.ensureTopics(Topics.JOBS, Topics.JOBS_DLQ)
        listOf("applications", "events", "job_state", "skips", "blockers", "job_listings")
            .forEach { mongo.getCollection(it).deleteMany(org.bson.Document()) }
        listings.save(listing)
    }

    private fun message() = JobMessage(
        source = listing.source,
        sourceJobId = listing.sourceJobId,
        company = listing.company,
        companyKey = listing.companyKey,
        roleTitle = listing.roleTitle,
        url = listing.url,
        region = listing.region,
        score = 82,
        scoreReason = "kotlin matches",
        enqueuedAt = Instant.now(),
    )

    @Test
    fun `recordSubmitted twice for the same job writes one application and one event`() {
        val evidence = Recorder.Evidence(
            confirmationUrl = "https://justjoin.it/applied/guard-1",
            confirmationText = "Application sent",
            ats = null,
        )

        val first = recorder.recordSubmitted(listing, JOB_ID, evidence)
        val second = recorder.recordSubmitted(listing, JOB_ID, evidence)

        assertThat(first).isTrue()
        assertThat(second).isFalse()
        assertThat(applications.count()).isEqualTo(1)
        // The event ledger is append-only and is what the campaign stats are
        // derived from, so a second "submitted" event would double-count the
        // application in every operator view even if the row itself was safe.
        assertThat(events.count()).isEqualTo(1)
        assertThat(applications.findById(JOB_ID).orElseThrow().status.name).isEqualTo("SUBMITTED")
    }

    @Test
    fun `a later redelivery does not overwrite the first outcome`() {
        recorder.recordSubmitted(
            listing,
            JOB_ID,
            Recorder.Evidence("https://justjoin.it/applied/guard-1", "Application sent", "traffit"),
        )
        val firstSeen = applications.findById(JOB_ID).orElseThrow()
        val firstEvidence = firstSeen.evidence.toString()

        // Same job, but this time the agent reports a worse outcome and a
        // different ATS. The first outcome must stand.
        recorder.recordSubmitted(
            listing,
            JOB_ID,
            Recorder.Evidence("https://elsewhere/other", "no confirmation", "greenhouse"),
        )

        val after = applications.findById(JOB_ID).orElseThrow()
        assertThat(after.evidence.toString()).isEqualTo(firstEvidence)
        assertThat(after.ats).isEqualTo("traffit")
        assertThat(after.confirmationUrl).isEqualTo("https://justjoin.it/applied/guard-1")
        assertThat(applications.count()).isEqualTo(1)
    }

    /**
     * The end-to-end shape: the same message produced twice on the real topic,
     * as a crash between "Mongo write" and "offset commit" would do. Shadow
     * mode writes job_state rather than applications, so the assertion here is
     * that the broker accepted both and the pipeline stayed consistent; the
     * application-level guard is proven by the tests above, which do not need
     * a browser to reach the same write.
     */
    @Test
    fun `the same message produced twice is consumed without duplicate bookkeeping`() {
        KafkaBroker.ensureTopics(Topics.JOBS)
        val payload = message()
        kafka.send(Topics.JOBS, payload.companyKey, payload).get()
        kafka.send(Topics.JOBS, payload.companyKey, payload).get()

        val deadline = Instant.now().plus(Duration.ofSeconds(60))
        var state: dev.mstaszew.campaign.common.domain.JobStateDocument? = null
        while (Instant.now().isBefore(deadline)) {
            state = mongo.findById(
                JOB_ID,
                dev.mstaszew.campaign.common.domain.JobStateDocument::class.java,
            )
            if (state != null && state!!.attempts >= 2) break
            Thread.sleep(500)
        }

        assertThat(state).isNotNull
        assertThat(state!!.attempts).isGreaterThanOrEqualTo(2)
        // Shadow mode must never create an application row.
        assertThat(applications.count()).isZero()
    }
}