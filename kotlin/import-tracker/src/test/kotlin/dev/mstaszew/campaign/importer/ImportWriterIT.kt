package dev.mstaszew.campaign.importer

import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import dev.mstaszew.campaign.common.repo.MongoReplicaSet
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The two contracts this importer has always had to keep, now against Mongo:
 * a re-run inserts nothing, and imported rows are visible to the same
 * companyKey field the dedupe queries read.
 */
@Testcontainers(disabledWithoutDocker = true)
class ImportWriterIT {

    private val databaseName = "import_writer_it"

    private fun <T> withDb(block: (MongoDatabase) -> T): T =
        MongoClients.create(MongoReplicaSet.connectionUri).use { client: MongoClient ->
            block(client.getDatabase(databaseName))
        }

    // No JavaTimeModule on purpose: both parsers read instants out of the JSON
    // strings themselves (Instant.parse, then OffsetDateTime as a fallback),
    // because tracker.json and events.jsonl carry timestamps in mixed formats.
    private val mapper = ObjectMapper()

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource(name)!!.readText()

    @BeforeEach
    fun clean() {
        withDb { db ->
            listOf("applications", "skips", "blockers", "events").forEach { db.getCollection(it).drop() }
        }
    }

    @Test
    fun `imports fixture and is idempotent on rerun`() {
        val model = TrackerParser(mapper).parse(fixture("fixture-tracker.json"))
        val events = EventsParser(mapper).parse(fixture("fixture-events.jsonl"))

        val first = withDb { ImportWriter(it).importAll(model, events) }
        assertThat(first.applicationsInserted).isEqualTo(3)
        assertThat(first.skipsInserted).isEqualTo(3)
        assertThat(first.blockersInserted).isEqualTo(1)
        assertThat(first.eventsInserted).isEqualTo(2)

        val counts = withDb { db ->
            mapOf(
                "applications" to db.getCollection("applications").countDocuments(),
                "skips" to db.getCollection("skips").countDocuments(),
                "blockers" to db.getCollection("blockers").countDocuments(),
                "events" to db.getCollection("events").countDocuments(),
            )
        }
        assertThat(counts).containsEntry("applications", 3L)
        assertThat(counts).containsEntry("skips", 3L)
        assertThat(counts).containsEntry("blockers", 1L)
        assertThat(counts).containsEntry("events", 2L)

        val second = withDb { ImportWriter(it).importAll(model, events) }
        assertThat(second.applicationsInserted).isZero()
        assertThat(second.skipsInserted).isZero()
        assertThat(second.blockersInserted).isZero()
        assertThat(second.eventsInserted).isZero() // events collection not empty

        val after = withDb { db ->
            mapOf(
                "applications" to db.getCollection("applications").countDocuments(),
                "skips" to db.getCollection("skips").countDocuments(),
            )
        }
        assertThat(after).containsEntry("applications", 3L)
        assertThat(after).containsEntry("skips", 3L)
    }

    /**
     * The whole point of porting the tracker: the dedupe queries read these
     * fields, so the importer must write the exact field names they query.
     */
    @Test
    fun `imported applications are visible to dedup queries`() {
        val model = TrackerParser(mapper).parse(fixture("fixture-tracker.json"))
        withDb { ImportWriter(it).importAll(model, emptyList()) }

        val companyHit = withDb { db ->
            db.getCollection("applications")
                .countDocuments(Document("companyKey", "funds-tech"))
        }
        assertThat(companyHit).isEqualTo(1)

        val idHit = withDb { db ->
            db.getCollection("applications").countDocuments(Document("_id", "justjoin:jj-9"))
        }
        assertThat(idHit).isEqualTo(1)
    }

    @Test
    fun `nested json is stored as a queryable subdocument`() {
        val model = TrackerParser(mapper).parse(fixture("fixture-tracker.json"))
        withDb { ImportWriter(it).importAll(model, emptyList()) }

        // 'salary.min' would be impossible against the old jsonb-as-text column
        // without a text index; as a subdocument it is an ordinary predicate.
        val byEvidence = withDb { db ->
            db.getCollection("applications")
                .countDocuments(Document("evidence.type", "portal_confirmation"))
        }
        assertThat(byEvidence).isGreaterThanOrEqualTo(1L)
    }
}