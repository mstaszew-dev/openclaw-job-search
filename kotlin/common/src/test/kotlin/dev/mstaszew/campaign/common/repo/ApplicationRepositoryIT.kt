package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

@Testcontainers(disabledWithoutDocker = true)
@DataMongoTest(properties = ["spring.data.mongodb.auto-index-creation=false"])
class ApplicationRepositoryIT {

    @Autowired
    lateinit var applications: ApplicationRepository

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mongoUri(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") { MongoReplicaSet.connectionUri }
            registry.add("spring.data.mongodb.database") { "application_repository_it" }
        }
    }

    /** The container is shared across the module; start each test clean. */
    @BeforeEach
    fun clean() {
        applications.deleteAll()
    }

    private fun saveApp(
        id: String,
        companyKey: String,
        url: String,
        status: ApplicationStatus = ApplicationStatus.SUBMITTED,
    ): ApplicationEntity =
        applications.save(
            ApplicationEntity(
                id = id,
                source = id.substringBefore(":"),
                sourceJobId = id.substringAfter(":"),
                company = "C $companyKey",
                companyKey = companyKey,
                roleTitle = "Backend Developer",
                url = url,
                status = status,
                appliedAt = Instant.now(),
            ),
        )

    @Test
    fun `matches by id`() {
        saveApp("nofluffjobs:123", "acme", "https://example.com/a")
        assertThat(applications.findFirstByIdOrUrl("nofluffjobs:123", "https://nomatch")).isNotNull
    }

    @Test
    fun `matches by normalized url`() {
        saveApp("justjoin:9", "beta", "https://example.com/b")
        assertThat(applications.findFirstByIdOrUrl("no:match", "https://example.com/b")).isNotNull
    }

    @Test
    fun `no match returns null`() {
        assertThat(applications.findFirstByIdOrUrl("no:match", "https://nomatch")).isNull()
        assertThat(applications.findFirstByCompanyKey("ghost")).isNull()
    }

    @Test
    fun `matches by company key`() {
        saveApp("theprotocol:7", "google", "https://example.com/c")
        assertThat(applications.findFirstByCompanyKey("google")).isNotNull
    }

    /**
     * The reason these two reads are hand-written rather than derived: with
     * several rows matching, Mongo must return the same one every time. The
     * ascending _id sort is what keeps the dedupe reference written into the
     * event ledger reproducible between runs.
     */
    @Test
    fun `first match is deterministic when several rows match`() {
        saveApp("justjoin:bbb", "dup", "https://example.com/same")
        saveApp("justjoin:aaa", "dup", "https://example.com/same")

        val first = applications.findFirstByCompanyKey("dup")
        val second = applications.findFirstByCompanyKey("dup")

        assertThat(first?.id).isEqualTo("justjoin:aaa")
        assertThat(second?.id).isEqualTo(first?.id)
    }

    @Test
    fun `counts by status`() {
        saveApp("nofluffjobs:s1", "k1", "https://example.com/1", ApplicationStatus.ATTEMPTED)
        saveApp("nofluffjobs:s2", "k2", "https://example.com/2")
        assertThat(applications.countByStatus(ApplicationStatus.SUBMITTED)).isEqualTo(1)
        assertThat(applications.countByStatus(ApplicationStatus.ATTEMPTED)).isEqualTo(1)
    }

    /**
     * The double-apply guard, in the shape a redelivered message arrives:
     * the same document offered twice must leave exactly one row.
     */
    @Test
    fun `insertIfAbsent writes once and refuses the second attempt`() {
        val app = saveApp("justjoin:dedupe", "guard", "https://example.com/g")

        assertThat(applications.insertIfAbsent(app)).isFalse()
        assertThat(applications.count()).isEqualTo(1)
    }

    @Test
    fun `insertIfAbsent does not overwrite an existing outcome`() {
        saveApp("justjoin:guard", "guard", "https://example.com/g")

        val inserted = try {
            applications.insertIfAbsent(
                ApplicationEntity(
                    id = "justjoin:guard",
                    company = "renamed",
                    status = ApplicationStatus.ATTEMPTED,
                ),
            )
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            false
        }

        assertThat(inserted).isFalse()
        assertThat(applications.findById("justjoin:guard").orElseThrow().company).isEqualTo("C guard")
    }
}