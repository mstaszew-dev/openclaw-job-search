package dev.mstaszew.campaign.importer

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

@Testcontainers(disabledWithoutDocker = true)
class ImportWriterIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("campaign")
            .withUsername("campaign")
            .withPassword("campaign")
    }

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource(name)!!.readText()

    /** Both tests share the container; each starts from a clean schema. */
    @org.junit.jupiter.api.BeforeEach
    fun truncate() {
        withDb { c ->
            c.createStatement().use { st ->
                st.execute("TRUNCATE applications, skips, blockers, events RESTART IDENTITY")
            }
        }
    }

    private fun <T> withDb(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)

    private fun migrate() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    private fun count(table: String): Int = withDb { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM $table").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    @Test
    fun `imports fixture and is idempotent on rerun`() {
        migrate()
        val mapper = ObjectMapper()
        val model = TrackerParser(mapper).parse(fixture("fixture-tracker.json"))
        val events = EventsParser(mapper).parse(fixture("fixture-events.jsonl"))

        val first = withDb { ImportWriter(it).importAll(model, events) }
        assertThat(first.applicationsInserted).isEqualTo(3)
        assertThat(first.skipsInserted).isEqualTo(3)
        assertThat(first.blockersInserted).isEqualTo(1)
        assertThat(first.eventsInserted).isEqualTo(2)
        assertThat(count("applications")).isEqualTo(3)
        assertThat(count("skips")).isEqualTo(3)
        assertThat(count("blockers")).isEqualTo(1)
        assertThat(count("events")).isEqualTo(2)

        val second = withDb { ImportWriter(it).importAll(model, events) }
        assertThat(second.applicationsInserted).isZero()
        assertThat(second.skipsInserted).isZero()
        assertThat(second.blockersInserted).isZero()
        assertThat(second.eventsInserted).isZero() // events table not empty
        assertThat(count("applications")).isEqualTo(3)
        assertThat(count("skips")).isEqualTo(3)
    }

    @Test
    fun `imported applications are visible to dedup queries`() {
        migrate()
        val mapper = ObjectMapper()
        val model = TrackerParser(mapper).parse(fixture("fixture-tracker.json"))
        withDb { ImportWriter(it).importAll(model, emptyList()) }

        val companyHit = withDb { c ->
            c.prepareStatement("SELECT count(*) FROM applications WHERE company_key = 'funds-tech'").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertThat(companyHit).isEqualTo(1)
    }
}
