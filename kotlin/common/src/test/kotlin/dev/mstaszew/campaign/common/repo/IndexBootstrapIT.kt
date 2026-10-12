package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.IndexBootstrap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Postgres validated its schema at boot (ddl-auto=validate) and Flyway owned
 * the DDL. Mongo has neither, so IndexBootstrap creates the indexes and this
 * test is the substitute for that fail-fast.
 *
 * It matters most for the unique index on job_listings(source, sourceJobId):
 * without it a second finder run silently inserts duplicate listings, and
 * nothing anywhere reports an error.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataMongoTest(properties = ["spring.data.mongodb.auto-index-creation=false"])
@Import(IndexBootstrap::class)
class IndexBootstrapIT {

    @Autowired
    lateinit var indexBootstrap: IndexBootstrap

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mongoUri(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") { MongoReplicaSet.connectionUri }
            registry.add("spring.data.mongodb.database") { "index_bootstrap_it" }
        }
    }

    /** ApplicationReadyEvent does not fire in a slice test, so call it directly. */
    @BeforeEach
    fun createIndexes() {
        indexBootstrap.ensureIndexes()
    }

    @Test
    fun `every declared index exists on its collection`() {
        indexBootstrap.definitions.forEach { ci ->
            val present = indexBootstrap.indexNames(ci.collection)
            val expected = ci.names
            assertThat(present).`as`("indexes on " + ci.collection).containsAll(expected)
        }
    }

    @Test
    fun `the listings source uniqueness constraint is present`() {
        assertThat(indexBootstrap.indexNames("job_listings"))
            .contains("uq_job_listings_source_job")
    }

    @Test
    fun `the skips dedupe tuple is indexed for the importer upsert`() {
        assertThat(indexBootstrap.indexNames("skips")).contains("uq_skips_dedupe")
    }

    @Test
    fun `ensureIndexes is idempotent`() {
        indexBootstrap.ensureIndexes()
        indexBootstrap.ensureIndexes()
        indexBootstrap.definitions.forEach { ci ->
            assertThat(indexBootstrap.indexNames(ci.collection))
                .containsAll(ci.names)
        }
    }
}