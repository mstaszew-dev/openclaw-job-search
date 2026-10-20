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

    /**
     * tracker.json is the live source of truth: when it advances a row the
     * k3s-era import left stale (attempted -> submitted, null -> real
     * appliedAt), a re-import must repair the existing document instead of
     * leaving it frozen at its first-inserted state.
     */
    @Test
    fun `repairs an existing application row when the tracker advances it`() {
        val stale = TrackerParser(mapper).parse(
            """{"applications":[{"id":"x:stale","company":"Stale Co","companyKey":"stale-co",
                 "status":"attempted"}],
               "skipped":[],"blockers":[]}""",
        )
        val advanced = TrackerParser(mapper).parse(
            """{"applications":[{"id":"x:stale","company":"Stale Co","companyKey":"stale-co",
                 "status":"submitted","appliedAt":"2026-10-18T20:00:00Z",
                 "confirmationUrl":"https://portal.example/confirm/1"}],
               "skipped":[],"blockers":[]}""",
        )

        val first = withDb { ImportWriter(it).importAll(stale, emptyList()) }
        assertThat(first.applicationsInserted).isEqualTo(1)

        val createdAt = withDb { db ->
            db.getCollection("applications").find(Document("_id", "x:stale")).first()!!
                .getDate("createdAt").toInstant()
        }
        val updatedAt = withDb { db ->
            db.getCollection("applications").find(Document("_id", "x:stale")).first()!!
                .getDate("updatedAt").toInstant()
        }

        val second = withDb { ImportWriter(it).importAll(advanced, emptyList()) }
        assertThat(second.applicationsInserted).isZero()
        assertThat(second.applicationsUpdated).isEqualTo(1)

        val repaired = withDb { db ->
            db.getCollection("applications").find(Document("_id", "x:stale")).first()!!
        }
        assertThat(repaired.getString("status")).isEqualTo("SUBMITTED")
        assertThat(repaired.getDate("appliedAt").toInstant())
            .isEqualTo(java.time.Instant.parse("2026-10-18T20:00:00Z"))
        assertThat(repaired.getString("confirmationUrl")).isEqualTo("https://portal.example/confirm/1")
        // Mongo-owned bookkeeping survives the repair untouched.
        assertThat(repaired.getDate("createdAt").toInstant()).isEqualTo(createdAt)
        assertThat(repaired.getDate("updatedAt").toInstant()).isEqualTo(updatedAt)
    }

    /**
     * A tracker variant that lacks confirmation evidence must never erase
     * evidence a previous import already recorded (legacy attempted rows carry
     * null confirmation fields; the k3s worker may have written the real ones).
     */
    @Test
    fun `never nulls confirmation evidence on update`() {
        val withEvidence = TrackerParser(mapper).parse(
            """{"applications":[{"id":"x:ev","company":"Ev Co","companyKey":"ev-co",
                 "status":"submitted","appliedAt":"2026-06-17T10:00:00Z",
                 "confirmationUrl":"https://portal.example/confirm/9",
                 "confirmationText":"application received"}],
               "skipped":[],"blockers":[]}""",
        )
        val withoutEvidence = TrackerParser(mapper).parse(
            """{"applications":[{"id":"x:ev","company":"Ev Co","companyKey":"ev-co",
                 "status":"submitted","appliedAt":"2026-06-17T10:00:00Z"}],
               "skipped":[],"blockers":[]}""",
        )

        withDb { ImportWriter(it).importAll(withEvidence, emptyList()) }
        val second = withDb { ImportWriter(it).importAll(withoutEvidence, emptyList()) }

        assertThat(second.applicationsUpdated).isZero()
        val row = withDb { db ->
            db.getCollection("applications").find(Document("_id", "x:ev")).first()!!
        }
        assertThat(row.getString("confirmationUrl")).isEqualTo("https://portal.example/confirm/9")
        assertThat(row.getString("confirmationText")).isEqualTo("application received")
    }

    /** Repair semantics must not break the core promise: same file, zero writes. */
    @Test
    fun `rerun with unchanged tracker modifies nothing`() {
        val model = TrackerParser(mapper).parse(fixture("fixture-tracker.json"))

        withDb { ImportWriter(it).importAll(model, emptyList()) }
        val second = withDb { ImportWriter(it).importAll(model, emptyList()) }

        assertThat(second.applicationsInserted).isZero()
        assertThat(second.applicationsUpdated).isZero()
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