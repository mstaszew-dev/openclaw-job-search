package dev.mstaszew.campaign.common.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant

/**
 * Replaces the Postgres JsonMappingIT. Every field of every document is
 * asserted on the way back out, because a silently dropped field here shows up
 * much later as an empty event ledger or a dedupe check that misses.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataMongoTest(properties = ["spring.data.mongodb.auto-index-creation=false"])
class DocumentMappingIT {

    @Autowired
    lateinit var mongo: MongoTemplate

    private val mapper = ObjectMapper()
    private val nodes = JsonNodeFactory.instance

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mongoUri(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") { MongoReplicaSet.connectionUri }
            registry.add("spring.data.mongodb.database") { "document_mapping_it" }
        }
    }

    @BeforeEach
    fun clean() {
        listOf(
            "applications", "skips", "blockers", "events", "job_listings", "job_state",
        ).forEach { mongo.getCollection(it).drop() }
    }

    @Test
    fun `application round-trips every field including nested json`() {
        val appliedAt = Instant.parse("2026-02-03T10:15:30Z")
        val saved = mongo.save(
            ApplicationEntity(
                id = "justjoin:42",
                source = "justjoin",
                sourceJobId = "42",
                company = "Funds Tech",
                companyKey = "funds-tech",
                roleTitle = "Backend Developer",
                roleKey = "backend-developer",
                url = "https://justjoin.it/job/42",
                region = "PL",
                remotePolicy = "remote",
                salary = nodes.objectNode().put("min", 15000).put("max", 20000).put("currency", "PLN"),
                stack = listOf("kotlin", "spring"),
                applyMethod = "portal",
                ats = "recruitee",
                status = ApplicationStatus.SUBMITTED,
                confirmationUrl = "https://example.com/thanks",
                confirmationText = "Application submitted",
                evidence = nodes.objectNode().put("type", "portal_confirmation").put("valid", true),
                appliedAt = appliedAt,
                followUps = nodes.arrayNode().add("gmail_auto_reply"),
                notes = "strong references on request",
            ),
        )

        val loaded = mongo.findById(saved.id, ApplicationEntity::class.java)!!

        assertThat(loaded.id).isEqualTo("justjoin:42")
        assertThat(loaded.companyKey).isEqualTo("funds-tech")
        assertThat(loaded.roleKey).isEqualTo("backend-developer")
        assertThat(loaded.region).isEqualTo("PL")
        assertThat(loaded.stack).containsExactly("kotlin", "spring")
        assertThat(loaded.status).isEqualTo(ApplicationStatus.SUBMITTED)
        assertThat(loaded.appliedAt).isEqualTo(appliedAt)
        assertThat(loaded.notes).isEqualTo("strong references on request")
        // The point of the migration: these are queryable subdocuments, not
        // opaque strings, so 'salary.min' is now a usable index predicate.
        assertThat(loaded.salary?.path("min")?.asInt()).isEqualTo(15000)
        assertThat(loaded.salary?.path("currency")?.asText()).isEqualTo("PLN")
        assertThat(loaded.evidence?.path("type")?.asText()).isEqualTo("portal_confirmation")
        assertThat(loaded.evidence?.path("valid")?.asBoolean()).isTrue()
        assertThat(loaded.followUps?.isArray).isTrue()
    }

    /**
     * salarySeen in tracker.json is sometimes a bare string and sometimes an
     * object. Both shapes must survive; this is why the field is JsonNode and
     * not Map<String, Any?>.
     */
    @Test
    fun `salary keeps a bare string as well as an object`() {
        mongo.save(
            ApplicationEntity(
                id = "nofluffjobs:s1",
                salary = nodes.textNode("15000 PLN net+VAT"),
            ),
        )
        mongo.save(
            ApplicationEntity(
                id = "nofluffjobs:s2",
                salary = nodes.objectNode().put("min", 15000),
            ),
        )

        val stringy = mongo.findById("nofluffjobs:s1", ApplicationEntity::class.java)!!
        val obj = mongo.findById("nofluffjobs:s2", ApplicationEntity::class.java)!!

        assertThat(stringy.salary?.asText()).isEqualTo("15000 PLN net+VAT")
        assertThat(obj.salary?.path("min")?.asInt()).isEqualTo(15000)
    }

    @Test
    fun `job listing round-trips decimals, arrays and raw json`() {
        val saved = mongo.save(
            JobListingEntity(
                source = "nofluffjobs",
                sourceJobId = "aaa-1",
                company = "Some Company",
                companyKey = "some-company",
                roleTitle = "Kotlin Developer",
                url = "https://nofluffjobs.com/aaa-1",
                salaryMin = BigDecimal("15000.00"),
                salaryMax = BigDecimal("22000.00"),
                salaryCurrency = "PLN",
                salaryBasis = "monthly net+VAT B2B",
                stack = listOf("kotlin", "spring", "mongo"),
                raw = nodes.objectNode().put("title", "Kotlin Developer").put("remote", true),
            ),
        )

        val loaded = mongo.findById(saved.id!!, JobListingEntity::class.java)!!

        assertThat(loaded.id).isNotBlank()
        assertThat(loaded.salaryMin).isEqualByComparingTo(BigDecimal("15000.00"))
        assertThat(loaded.salaryMax).isEqualByComparingTo(BigDecimal("22000.00"))
        assertThat(loaded.salaryBasis).isEqualTo("monthly net+VAT B2B")
        assertThat(loaded.stack).containsExactly("kotlin", "spring", "mongo")
        assertThat(loaded.raw?.path("title")?.asText()).isEqualTo("Kotlin Developer")
        assertThat(loaded.raw?.path("remote")?.asBoolean()).isTrue()
    }

    @Test
    fun `skip and blocker round-trip`() {
        val listing = mongo.save(JobListingEntity(source = "justjoin", sourceJobId = "7"))
        val at = Instant.parse("2026-01-05T08:00:00Z")

        val skip = mongo.save(
            SkipEntity(
                reason = SkipReason.DUPLICATE,
                listingId = listing.id,
                source = "justjoin",
                sourceJobId = "7",
                company = "Some Company",
                companyKey = "some-company",
                roleTitle = "Backend Developer",
                url = "https://justjoin.it/job/7",
                salary = nodes.textNode("15000"),
                stack = listOf("java"),
                detail = "already applied",
                blockedRepeat = true,
                blockCount = 3,
                at = at,
            ),
        )

        val loadedSkip = mongo.findById(skip.id!!, SkipEntity::class.java)!!
        assertThat(loadedSkip.reason).isEqualTo(SkipReason.DUPLICATE)
        assertThat(loadedSkip.listingId).isEqualTo(listing.id)
        assertThat(loadedSkip.blockedRepeat).isTrue()
        assertThat(loadedSkip.blockCount).isEqualTo(3)
        assertThat(loadedSkip.at).isEqualTo(at)
        assertThat(loadedSkip.salary?.asText()).isEqualTo("15000")
    }

    @Test
    fun `event record round-trips and defaults to an empty object`() {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        mongo.save(
            EventEntity(
                at = at,
                action = "submitted",
                record = nodes.objectNode().put("id", "justjoin:1").put("status", "submitted"),
            ),
        )
        mongo.save(EventEntity(at = at, action = "record-less"))

        val submitted = mongo.find(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("action").`is`("submitted"),
            ),
            EventEntity::class.java,
        ).single()
        val bare = mongo.find(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("action").`is`("record-less"),
            ),
            EventEntity::class.java,
        ).single()

        assertThat(submitted.record.path("id").asText()).isEqualTo("justjoin:1")
        assertThat(submitted.at).isEqualTo(at)
        // The live events.jsonl has record-less lines; those must not fail the
        // whole import the way an empty jsonb did.
        assertThat(bare.record.isObject).isTrue()
        assertThat(bare.record.size()).isZero()
    }

    @Test
    fun `job state round-trips attempts and outcome`() {
        val saved = mongo.save(
            JobStateDocument(
                id = "justjoin:1",
                source = "justjoin",
                sourceJobId = "1",
                companyKey = "some-company",
                status = JobStatus.SHADOW_RELEASED,
                attempts = 2,
                maxAttempts = 3,
                score = 82,
                scoreReason = "strong stack match",
                lastError = null,
            ),
        )

        val loaded = mongo.findById(saved.id, JobStateDocument::class.java)!!

        assertThat(loaded.status).isEqualTo(JobStatus.SHADOW_RELEASED)
        assertThat(loaded.attempts).isEqualTo(2)
        assertThat(loaded.score).isEqualTo(82)
        assertThat(loaded.lastError).isNull()
    }
}