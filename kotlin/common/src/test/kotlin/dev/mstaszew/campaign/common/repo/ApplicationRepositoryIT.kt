package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=validate"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApplicationRepositoryIT {

    @Autowired
    lateinit var applications: ApplicationRepository

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            val c = PostgresContainerSupport.container
            registry.add("spring.datasource.url", c::getJdbcUrl)
            registry.add("spring.datasource.username", c::getUsername)
            registry.add("spring.datasource.password", c::getPassword)
        }
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
        assertThat(applications.findFirstByIdOrUrlOrderById("nofluffjobs:123", "https://nomatch")).isNotNull
    }

    @Test
    fun `matches by normalized url`() {
        saveApp("justjoin:9", "beta", "https://example.com/b")
        assertThat(applications.findFirstByIdOrUrlOrderById("no:match", "https://example.com/b")).isNotNull
    }

    @Test
    fun `no match returns null`() {
        assertThat(applications.findFirstByIdOrUrlOrderById("no:match", "https://nomatch")).isNull()
        assertThat(applications.findFirstByCompanyKeyOrderById("ghost")).isNull()
    }

    @Test
    fun `matches by company key`() {
        saveApp("theprotocol:7", "google", "https://example.com/c")
        assertThat(applications.findFirstByCompanyKeyOrderById("google")).isNotNull
    }

    @Test
    fun `counts by status`() {
        saveApp("nofluffjobs:s1", "k1", "https://example.com/1", ApplicationStatus.ATTEMPTED)
        saveApp("nofluffjobs:s2", "k2", "https://example.com/2")
        assertThat(applications.countByStatus(ApplicationStatus.SUBMITTED)).isGreaterThanOrEqualTo(1)
        assertThat(applications.countByStatus(ApplicationStatus.ATTEMPTED)).isGreaterThanOrEqualTo(1)
    }
}
