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
import java.time.OffsetDateTime
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
)

/** Maps tracker.json (the live Python campaign state) onto the Postgres entities. */
class TrackerParser(private val mapper: ObjectMapper) {

    fun parse(json: String): TrackerModel {
        val root = mapper.readTree(json)
        val warnings = mutableListOf<String>()
        val applications = root.path("applications").mapNotNull { toApplication(it, warnings) }
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
        val statusText = n.path("status").asText("submitted")
        val status = when (statusText) {
            "attempted" -> ApplicationStatus.ATTEMPTED
            "submitted" -> ApplicationStatus.SUBMITTED
            else -> {
                warnings.add("application $id: unknown status '$statusText', imported as SUBMITTED")
                ApplicationStatus.SUBMITTED
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
            salary = n.path("salarySeen").takeIf { it.isMissingNode.not() && !it.isNull }?.toString(),
            stack = stackNode.stackList(),
            applyMethod = n.path("applyMethod").asText(null),
            ats = n.path("ats").asText(null),
            status = status,
            confirmationUrl = n.path("confirmationUrl").asText(null),
            confirmationText = n.path("confirmationText").asText(null),
            evidence = n.path("evidence").takeIf { it.isMissingNode.not() && !it.isNull }?.toString(),
            appliedAt = appliedAt,
            followUps = n.path("followUps").takeIf { it.isMissingNode.not() && !it.isNull }?.toString(),
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
            salary = n.path("salarySeen").takeIf { it.isMissingNode.not() && !it.isNull }?.toString(),
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

    private fun JsonNode.stackList(): List<String> = when {
        isArray -> mapNotNull { it.asText(null)?.trim()?.takeIf(String::isNotEmpty) }
        isTextual && !asText().isBlank() -> listOf(asText().trim())
        else -> emptyList()
    }

    private fun String.parseInstant(): Instant? = runCatching { Instant.parse(this) }
        .recoverCatching { OffsetDateTime.parse(this, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
        .getOrNull()
}
