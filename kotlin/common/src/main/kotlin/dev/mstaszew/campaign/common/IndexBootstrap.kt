package dev.mstaszew.campaign.common

import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.index.IndexDefinition
import org.springframework.stereotype.Component

/**
 * Postgres owned its schema with Flyway DDL and Hibernate validated the mapping
 * against it at boot (ddl-auto=validate). Mongo has neither, so indexes are
 * declared here and created at startup.
 *
 * This is not decoration. The load-bearing index is the implicit unique one on
 * applications._id: it is what makes a double apply impossible, and without it
 * nothing fails loudly, a second application is simply written. That is why the
 * index list is data ([definitions]) rather than imperative calls, so
 * IndexBootstrapIT can assert the collection actually carries them.
 */
@Component
class IndexBootstrap(private val mongo: MongoTemplate) {

    /** collection name to the indexes it must carry. Single source of truth. */
    val definitions: List<CollectionIndexes> = listOf(
        CollectionIndexes(
            "job_listings",
            listOf(
                // Mirrors uq_job_listings_source_job. Without it two finder
                // runs can insert the same board listing twice.
                idx("source", "sourceJobId").unique().named("uq_job_listings_source_job"),
                idx("companyKey").named("idx_job_listings_company_key"),
                idx("url").named("idx_job_listings_url"),
            ),
        ),
        CollectionIndexes(
            "applications",
            listOf(
                idx("companyKey").named("idx_applications_company_key"),
                idx("url").named("idx_applications_url"),
                idx("appliedAt").named("idx_applications_applied_at"),
                // Deliberately NOT unique. existsByCompanyKeyAndRoleKey is a
                // question, not an invariant: the 1809 imported rows are the
                // source of truth and a unique index would fail the import
                // outright if any pair repeats. roleKey is nullable and Mongo
                // collapses every null into one value, which is another reason
                // not to make this a uniqueness constraint here.
                idx("companyKey", "roleKey").named("idx_applications_company_role"),
            ),
        ),
        CollectionIndexes(
            "skips",
            listOf(
                idx("companyKey").named("idx_skips_company_key"),
                idx("url").named("idx_skips_url"),
                // findFirstByReasonAndCompanyKey filters on exactly these two
                // fields, so they have to lead the index or the dedupe read
                // falls back to a collection scan.
                idx("reason", "companyKey").named("idx_skips_reason_company"),
                idx("reason", "url").named("idx_skips_reason_url"),
                // The importer upserts on this exact tuple to stay idempotent.
                // It is a separate index from the read indexes above precisely
                // because those two cannot serve a filter on all six fields.
                compound(
                    "uq_skips_dedupe",
                    listOf("reason", "source", "sourceJobId", "companyKey", "url", "at"),
                ),
            ),
        ),
        CollectionIndexes(
            "blockers",
            listOf(
                idx("companyKey").named("idx_blockers_company_key"),
                compound("uq_blockers_dedupe", listOf("companyKey", "reason", "at")),
            ),
        ),
        CollectionIndexes(
            "events",
            listOf(
                // findTop20ByOrderByAtDesc sorts the recent-events view.
                idx("at").named("idx_events_at"),
                compound("idx_events_action_at", listOf("action", "at")),
            ),
        ),
        CollectionIndexes(
            "job_state",
            listOf(idx("status").named("idx_job_state_status")),
        ),
    )

    @EventListener(ApplicationReadyEvent::class)
    fun ensureIndexes() {
        val log = LoggerFactory.getLogger(IndexBootstrap::class.java)
        for (ci in definitions) {
            val ops = mongo.indexOps(ci.collection)
            val before = ops.indexInfo.map { it.name }.toSet()
            ci.indexes.forEach { ops.ensureIndex(it) }
            val created = ops.indexInfo.map { it.name }.toSet() - before
            log.info("indexes ready on {}: {} (created now: {})", ci.collection, ci.indexes.size, created.sorted())
        }
    }

    /** Index names present on a collection, for tests and the status endpoint. */
    fun indexNames(collection: String): Set<String> =
        mongo.indexOps(collection).indexInfo.map { it.name }.toSet()

    /**
     * Ascending compound index over [fields], in order. Index() takes the first
     * key and .on() appends the rest, which is the only multi-key form Spring
     * Data MongoDB offers for Index (CompoundIndexDefinition needs a raw
     * BSON Document instead).
     */
    private fun idx(vararg fields: String): Index =
        fields.drop(1).fold(
            Index(fields.first(), Sort.Direction.ASC),
        ) { index, field -> index.on(field, Sort.Direction.ASC) }

    private fun compound(name: String, fields: List<String>): IndexDefinition =
        CompoundIndexDefinition(
            Document(fields.associateWith { 1 }),
        ).named(name)

    data class CollectionIndexes(val collection: String, val indexes: List<IndexDefinition>) {
        /**
         * The names [IndexBootstrapIT] asserts against. Read back off the
         * definition rather than kept as a parallel list, so the two cannot
         * drift; requireNotNull because a nameless index would otherwise blow
         * up here with a bare ClassCastException.
         */
        val names: Set<String> =
            indexes.map { requireNotNull(it.indexOptions["name"]) { "unnamed index on $collection" } as String }.toSet()
    }
}