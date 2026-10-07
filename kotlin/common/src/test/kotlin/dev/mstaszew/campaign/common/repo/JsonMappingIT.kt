package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers

/** Proves the JSONB and TEXT[] write paths round-trip on real Postgres. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=validate"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JsonMappingIT {

    @Autowired
    lateinit var listings: JobListingRepository

    @Autowired
    lateinit var events: EventRepository

    @Autowired
    lateinit var em: TestEntityManager

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

    @Test
    fun `jsonb and array columns round-trip`() {
        val saved = listings.save(
            JobListingEntity(
                source = "justjoin",
                sourceJobId = "jj-1",
                company = "Example SA",
                companyKey = "example",
                roleTitle = "Kotlin Developer",
                url = "https://example.com/jj-1",
                stack = listOf("kotlin", "spring boot", "postgresql"),
                raw = """{"title":"Kotlin Developer","remote":true}""",
                salaryMin = java.math.BigDecimal("15000"),
            ),
        )
        em.flush()
        em.clear()

        val loaded = listings.findById(saved.id).orElseThrow()
        assertThat(loaded.stack).containsExactly("kotlin", "spring boot", "postgresql")
        assertThat(loaded.raw).contains("\"Kotlin Developer\"")
        assertThat(loaded.salaryMin).isEqualByComparingTo("15000")
    }

    @Test
    fun `event record jsonb round-trips`() {
        val saved = events.save(
            EventEntity(action = "submitted", record = """{"id":"justjoin:1","status":"submitted"}"""),
        )
        em.flush()
        em.clear()

        val loaded = events.findById(saved.seq).orElseThrow()
        assertThat(loaded.action).isEqualTo("submitted")
        assertThat(loaded.record).contains("justjoin:1")
    }
}
