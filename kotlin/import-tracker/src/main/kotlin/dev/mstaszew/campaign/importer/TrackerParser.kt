package dev.mstaszew.campaign.importer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.dedupe.CompanyKeyNormalizer
import dev.mstaszew.campaign.common.dedupe.UrlNormalizer
import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import dev.mstaszew.campaign.common.domain.BlockerEntity
import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class TrackerStats(
    val submitted: Long,
    val skippedDuplicate: Long,
    val skippedSalary: Long,
    val skippedFilter: Long,
    val blockedManual: Long,
)

data class TrackerModel(
    val applications: List<ApplicationEntity>,
    val skips: List<SkipEntity>,
    val blockers: List<BlockerEntity>,
    val stats: TrackerStats,
    /** Non-fatal anomalies (unparsable appliedAt, unknown status, odd stack shapes). */
    val warnings: List<String>,
    /** How many applications[] entries collapsed into an earlier duplicate id. */
    val duplicateIdsCollapsed: Int,
)

/**
 * Maps tracker.json (the live Python campaign state) onto the Postgres entities.
 *
 * applications[] is the live source of truth, so the parser collapses duplicate
 * ids to the strongest variant before anything reaches Mongo: SUBMITTED beats
 * ATTEMPTED (a confirmed submission outranks a later unconfirmed re-apply),
 * then the later appliedAt wins (a re-apply supersedes the first submission),
 * then the earlier file position wins (pure tie = keep what python wrote
 * first). Without this the importer's unordered bulkWrite lets whichever
 * variant executes first win the insert - observed live on 2026-10-18, where
 * an ATTEMPTED re-apply variant raced in ahead of its SUBMITTED sibling.
 */
class TrackerParser(private val mapper: ObjectMapper) {

    fun parse(json: String): TrackerModel {
        val root = mapper.readTree(json)
        val warnings = mutableListOf<String>()
        val parsed = root.path("applications").mapNotNull { toApplication(it, warnings) }
        val applications = collapseDuplicates(parsed)
        val skips = root.path("skipped").mapNotNull { toSkip(it) }
        val blockers = root.path("blockers").mapNotNull { toBlocker(it) }
        val stats = root.path("stats")
        return TrackerModel(
            applications = applications,
            skips = skips,
            blockers = blockers,
            stats = TrackerStats(
                submitted = stats.path("submitted").asLong(0),
                skippedDuplicate = stats.path("skippedDuplicate").asLong(0),
                skippedSalary = stats.path("skippedSalary").asLong(0),
                skippedFilter = stats.path("skippedFilter").asLong(0),
                blockedManual = stats.path("blockedManual").asLong(0),
            ),
            warnings = warnings.toList(),
            duplicateIdsCollapsed = parsed.size - applications.size,
        )
    }

    private fun toApplication(n: JsonNode, warnings: MutableList<String>): ApplicationEntity? {
        val source = n.path("source").asText("").trim().takeIf(String::isNotEmpty)
        val sourceJobId = n.path("sourceJobId").asText("").trim().takeIf(String::isNotEmpty)
        val legacyId = n.path("id").asText("").trim().takeIf(String::isNotEmpty)

        // Canonical keyspace is source:sourceJobId; legacy slug ids are a last resort
        // so DedupService's id check can match rows built either way.
        val id = if (source != null && sourceJobId != null) "$source:$sourceJobId"
        else legacyId ?: return null

        val company = n.path("company").asText("")
        val rawUrl = n.path("jobUrl").asText(n.path("listingUrl").asText(null))
        // tracker.py's own counters treat attempted_no_confirmation as a
        // separate non-submitted bucket, so it must not inflate the submitted
        // KPI here either; an application with no status at all is an
        // unconfirmed apply until the tracker says otherwise.
        val statusText = n.path("status").asText("")
        val status = when (statusText) {
            "submitted" -> ApplicationStatus.SUBMITTED
            "attempted", "attempted_no_confirmation" -> ApplicationStatus.ATTEMPTED
            else -> {
                val reason = if (statusText.isBlank()) "missing status" else "unknown status '$statusText'"
                warnings.add("application $id: $reason, imported as ATTEMPTED")
                ApplicationStatus.ATTEMPTED
            }
        }
        val appliedAtRaw = n.path("appliedAt").asText(null)
        val appliedAt = appliedAtRaw?.parseInstant()
        if (appliedAtRaw != null && appliedAt == null) {
            warnings.add("application $id: unparsable appliedAt '$appliedAtRaw', stored as NULL")
        }
        val stackNode = n.path("stack")
        if (stackNode.isObject) {
            warnings.add("application $id: stack is an object, imported as empty")
        }
        return ApplicationEntity(
            id = id,
            source = source ?: id.substringBefore(':'),
            sourceJobId = sourceJobId ?: id.substringAfter(':', missingDelimiterValue = id),
            company = company,
            companyKey = n.path("companyKey").asText("").ifBlank { CompanyKeyNormalizer.normalize(company) },
            roleTitle = n.path("roleTitle").asText(""),
            roleKey = n.path("roleKey").asText(null),
            url = UrlNormalizer.normalize(rawUrl).ifBlank { null },
            region = n.path("region").asText("PL"),
            remotePolicy = n.path("remotePolicy").asText(null),
            salary = n.path("salarySeen").takeIf { it.isMissingNode.not() && !it.isNull },
            stack = stackNode.stackList(),
            applyMethod = n.path("applyMethod").asText(null),
            ats = n.path("ats").asText(null),
            status = status,
            confirmationUrl = n.path("confirmationUrl").asText(null),
            confirmationText = n.path("confirmationText").asText(null),
            evidence = n.path("evidence").takeIf { it.isMissingNode.not() && !it.isNull },
            appliedAt = appliedAt,
            followUps = n.path("followUps").takeIf { it.isMissingNode.not() && !it.isNull },
            notes = n.path("notes").asText(null),
        )
    }

    private fun toSkip(n: JsonNode): SkipEntity? {
        val reason = when (n.path("reason").asText("")) {
            "duplicate" -> SkipReason.DUPLICATE
            "salary" -> SkipReason.SALARY
            "filter" -> SkipReason.FILTER
            else -> return null // free-text legacy reasons are dropped by contract
        }
        val company = n.path("company").asText(null)
        return SkipEntity(
            reason = reason,
            source = n.path("source").asText(null),
            sourceJobId = n.path("sourceJobId").asText(null),
            company = company,
            companyKey = company?.let { n.path("companyKey").asText("").ifBlank { CompanyKeyNormalizer.normalize(it) } },
            roleTitle = n.path("roleTitle").asText(null),
            roleKey = n.path("roleKey").asText(null),
            url = UrlNormalizer.normalize(n.path("jobUrl").asText(n.path("listingUrl").asText(null))).ifBlank { null },
            region = n.path("region").asText(null),
            remotePolicy = n.path("remotePolicy").asText(null),
            salary = n.path("salarySeen").takeIf { it.isMissingNode.not() && !it.isNull },
            stack = n.path("stack").stackList(),
            detail = n.path("detail").asText(null),
            blockedRepeat = n.path("blockedRepeat").asBoolean(false),
            blockCount = n.path("blockCount").takeIf { it.isNumber }?.asInt(),
            at = requireTimestamp(n, "skip"),
        )
    }

    private fun toBlocker(n: JsonNode): BlockerEntity? {
        val company = n.path("company").asText(null)
        return BlockerEntity(
            source = n.path("source").asText(null),
            sourceJobId = n.path("sourceJobId").asText(null),
            company = company,
            companyKey = company?.let { n.path("companyKey").asText("").ifBlank { CompanyKeyNormalizer.normalize(it) } },
            roleTitle = n.path("roleTitle").asText(null),
            url = UrlNormalizer.normalize(n.path("jobUrl").asText(n.path("listingUrl").asText(null))).ifBlank { null },
            reason = n.path("reason").asText(n.path("blockReason").asText("unknown")),
            resolved = n.path("resolved").asBoolean(false),
            detail = n.path("detail").asText(null),
            at = requireTimestamp(n, "blocker"),
        )
    }

    /**
     * Skips and blockers use 'at' in their idempotency guards, so a missing or
     * unparsable value must fail the import instead of silently breaking it.
     */
    private fun requireTimestamp(n: JsonNode, kind: String): Instant {
        val raw = n.path("at").asText(null)
            ?: throw IllegalStateException("$kind row (company=${n.path("company").asText("?")}) has no 'at'")
        return raw.parseInstant()
            ?: throw IllegalStateException("$kind row (company=${n.path("company").asText("?")}) has unparsable 'at' '$raw'")
    }

    /**
     * tracker.json carries several legacy timestamp shapes. Zone-less
     * datetimes and date-only values are read as UTC: the tracker's writer
     * emits aware-UTC ISO strings everywhere else, so a naive value is a
     * legacy-UTC value, and a bare date pins the day without inventing a
     * wall-clock time. Genuinely malformed strings ('+00001' offsets,
     * '09-51-00Z') stay null under the unparsable-appliedAt warning.
     */
    private fun JsonNode.stackList(): List<String> = when {
        isArray -> mapNotNull { it.asText(null)?.trim()?.takeIf(String::isNotEmpty) }
        isTextual && !asText().isBlank() -> listOf(asText().trim())
        else -> emptyList()
    }

    private fun collapseDuplicates(parsed: List<ApplicationEntity>): List<ApplicationEntity> =
        parsed.groupBy { it.id }.map { (_, variants) ->
            variants.reduce { best, candidate ->
                if (rankOf(candidate) > rankOf(best)) candidate else best
            }
        }

    /** SUBMITTED outranks ATTEMPTED; a later appliedAt outranks an earlier one. */
    private fun rankOf(a: ApplicationEntity): VariantRank =
        VariantRank(
            submitted = a.status == ApplicationStatus.SUBMITTED,
            appliedAt = a.appliedAt ?: Instant.EPOCH,
        )

    private data class VariantRank(val submitted: Boolean, val appliedAt: Instant) : Comparable<VariantRank> {
        override fun compareTo(other: VariantRank): Int =
            submitted.compareTo(other.submitted).takeIf { it != 0 }
                ?: appliedAt.compareTo(other.appliedAt)
    }

    private fun String.parseInstant(): Instant? = runCatching { Instant.parse(this) }
        .recoverCatching { OffsetDateTime.parse(this, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
        .recoverCatching { LocalDateTime.parse(this, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC) }
        .recoverCatching { LocalDate.parse(this, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant() }
        .getOrNull()
}
