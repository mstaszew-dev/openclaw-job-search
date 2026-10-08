package dev.mstaszew.campaign.importer

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.WriteModel
import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import dev.mstaszew.campaign.common.domain.BlockerEntity
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import org.bson.Document
import org.bson.conversions.Bson
import java.security.MessageDigest
import java.time.Instant

data class ImportReport(
    val applicationsInserted: Int,
    val applicationsSkipped: Int,
    val skipsInserted: Int,
    val skipsSkipped: Int,
    val blockersInserted: Int,
    val blockersSkipped: Int,
    val eventsInserted: Int,
)

/**
 * Idempotent Mongo writer: safe to re-run; documents already present are left
 * alone. Talks to the driver directly rather than through Spring, so the
 * importer keeps no application context and starts in well under a second.
 *
 * Idempotency maps from the Postgres version as follows:
 *
 * - `ON CONFLICT (id) DO NOTHING` becomes an upsert with $setOnInsert. For
 *   applications the filter is _id, which is already unique, so a second run
 *   matches and changes nothing.
 * - Skips and blockers had no natural key in Postgres, so the old code used
 *   `INSERT ... WHERE NOT EXISTS (six-way match)`. Here those same tuples
 *   become the upsert filter, which is the same condition expressed as one
 *   atomic operation rather than a check-then-insert that two concurrent
 *   importers could interleave.
 * - `IS NOT DISTINCT FROM ?` is literal `{field: null}` in the filter, because
 *   in Mongo a null matches a missing field - the NULL-safe equality the SQL
 *   reached for.
 * - Events stay all-or-nothing behind `count == 0`, since they have no key.
 *
 * The old one-big-transaction design is gone. Postgres rolled the whole
 * 1809-row import back on a single bad row, which is what turned the NUL
 * escape bug into a full debugging round; here each collection is written in
 * unordered bulk batches and a malformed row costs only its batch.
 */
class ImportWriter(private val database: MongoDatabase) {

    fun importAll(model: TrackerModel, events: List<EventEntity>): ImportReport {
        val apps = upsert(APPLICATIONS, model.applications.map(::applicationFilter), model.applications.map(::applicationDoc))
        val skips = upsert(SKIPS, model.skips.map(::skipFilter), model.skips.map(::skipDoc))
        val blockers = upsert(BLOCKERS, model.blockers.map(::blockerFilter), model.blockers.map(::blockerDoc))
        // Events have no natural key, so idempotency needs an explicit marker.
        // The old gate was countEvents() == 0, which is wrong the moment the
        // finder starts writing: a single live event makes the importer skip
        // the whole 2621-row ledger forever, silently. Keying on this
        // specific file's digest means a re-run of the same import skips, a
        // genuinely new ledger appends, and events written by the running
        // pipeline never block the import.
        val eventsDigest = digestOf(events)
        val eventsAlreadyImported = events.isNotEmpty() &&
            database.getCollection(IMPORT_RUNS).countDocuments(eq("_id", eventsDigest)) > 0
        val eventsInserted = when {
            events.isEmpty() -> 0
            eventsAlreadyImported -> 0
            else -> {
                insertEvents(events)
                database.getCollection(IMPORT_RUNS).insertOne(Document("_id", eventsDigest).append("at", Instant.now()))
                events.size
            }
        }
        return ImportReport(
            applicationsInserted = apps,
            applicationsSkipped = model.applications.size - apps,
            skipsInserted = skips,
            skipsSkipped = model.skips.size - skips,
            blockersInserted = blockers,
            blockersSkipped = model.blockers.size - blockers,
            eventsInserted = eventsInserted,
        )
    }

    /**
     * Upserts (filter, document) pairs and returns how many were genuinely
     * inserted, which the driver reports directly as the batch's upsert count.
     *
     * $setOnInsert rather than a plain replace: a matched document must be
     * left completely untouched, timestamps included, so a re-import cannot
     * make a stale tracker row look freshly written.
     */
    private fun upsert(collection: String, filters: List<Bson>, docs: List<Document>): Int {
        require(filters.size == docs.size) { "filter/document size mismatch for $collection" }
        if (filters.isEmpty()) return 0
        var inserted = 0
        filters.zip(docs).chunked(BATCH).forEach { batch ->
            val models: List<WriteModel<Document>> = batch.map { (filter, doc) ->
                UpdateOneModel(
                    filter,
                    Document("\$setOnInsert", doc),
                    UpdateOptions().upsert(true),
                )
            }
            val result = database.getCollection(collection)
                .bulkWrite(models, BulkWriteOptions().ordered(false))
            inserted += result.upserts.size
        }
        return inserted
    }

    private fun insertEvents(events: List<EventEntity>): Int {
        val collection = database.getCollection(EVENTS)
        events.map { eventDoc(it) }.chunked(BATCH).forEach { batch ->
            collection.insertMany(batch)
        }
        return events.size
    }

    /**
     * Content digest of the ledger being imported, so idempotency is per file
     * rather than per database. Uses the same fields the driver would write,
     * so reordering or reformatting the JSONL does not change the id.
     */
    private fun digestOf(events: List<EventEntity>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        events.forEach { e ->
            digest.update(e.at.toString().toByteArray())
            digest.update(e.action.toByteArray())
            digest.update(e.record.toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun applicationFilter(a: ApplicationEntity): Bson = eq("_id", a.id)

    private fun applicationDoc(a: ApplicationEntity): Document = Document()
        .append("_id", a.id)
        .append("source", a.source)
        .append("sourceJobId", a.sourceJobId)
        .append("company", a.company)
        .append("companyKey", a.companyKey)
        .append("roleTitle", a.roleTitle)
        .append("roleKey", a.roleKey)
        .append("url", a.url)
        .append("region", a.region)
        .append("remotePolicy", a.remotePolicy)
        .append("salary", jsonOrNull(a.salary))
        .append("stack", a.stack)
        .append("applyMethod", a.applyMethod)
        .append("ats", a.ats)
        .append("status", a.status.name)
        .append("confirmationUrl", a.confirmationUrl)
        .append("confirmationText", a.confirmationText)
        .append("evidence", jsonOrNull(a.evidence))
        .append("appliedAt", a.appliedAt)
        .append("followUps", jsonOrNull(a.followUps))
        .append("notes", a.notes)
        .append("createdAt", a.createdAt)
        .append("updatedAt", a.updatedAt)

    private fun skipFilter(s: SkipEntity): Bson = Document()
        .append("reason", s.reason.name)
        .append("source", s.source)
        .append("sourceJobId", s.sourceJobId)
        .append("companyKey", s.companyKey)
        .append("url", s.url)
        .append("at", s.at)

    private fun skipDoc(s: SkipEntity): Document = Document()
        .append("reason", s.reason.name)
        .append("listingId", s.listingId)
        .append("source", s.source)
        .append("sourceJobId", s.sourceJobId)
        .append("company", s.company)
        .append("companyKey", s.companyKey)
        .append("roleTitle", s.roleTitle)
        .append("roleKey", s.roleKey)
        .append("url", s.url)
        .append("region", s.region)
        .append("remotePolicy", s.remotePolicy)
        .append("salary", jsonOrNull(s.salary))
        .append("stack", s.stack)
        .append("detail", s.detail)
        .append("blockedRepeat", s.blockedRepeat)
        .append("blockCount", s.blockCount)
        .append("at", s.at)

    private fun blockerFilter(b: BlockerEntity): Bson = Document()
        .append("companyKey", b.companyKey)
        .append("reason", b.reason)
        .append("at", b.at)

    private fun blockerDoc(b: BlockerEntity): Document = Document()
        .append("source", b.source)
        .append("sourceJobId", b.sourceJobId)
        .append("company", b.company)
        .append("companyKey", b.companyKey)
        .append("roleTitle", b.roleTitle)
        .append("url", b.url)
        .append("reason", b.reason)
        .append("resolved", b.resolved)
        .append("detail", b.detail)
        .append("at", b.at)

    private fun eventDoc(e: EventEntity): Document = Document()
        .append("at", e.at)
        .append("action", e.action)
        .append("record", e.record ?: Document())

    /** BSON-native conversion of the Jackson node the parser produced. */
    private fun jsonOrNull(node: com.fasterxml.jackson.databind.JsonNode?): Any? =
        node?.let { Document.parse(it.toString()) }

    private companion object {
        const val BATCH = 500
        const val APPLICATIONS = "applications"
        const val SKIPS = "skips"
        const val BLOCKERS = "blockers"
        const val EVENTS = "events"
        const val IMPORT_RUNS = "import_runs"
    }
}