package dev.mstaszew.campaign.finder.policy

import dev.mstaszew.campaign.common.domain.JobListingEntity

enum class IneligibilityReason {
    SOURCE,
    REGION,
    NOT_REMOTE,
    STACK,
    EXCLUDED_TITLE,
    EXCLUDED_KEYWORD,
    SALARY_BELOW_FLOOR,
}

data class EligibilityVerdict(val eligible: Boolean, val reason: IneligibilityReason? = null, val detail: String = "")

/**
 * Deterministic pre-LLM gate. Mirrors the campaign targeting contract; the LLM
 * CV-alignment score is a separate, second gate.
 */
class EligibilityPolicy(private val props: EligibilityProperties) {

    fun check(listing: JobListingEntity): EligibilityVerdict {
        if (listing.source.lowercase() !in props.allowedSources.map { it.lowercase() }) {
            return EligibilityVerdict(false, IneligibilityReason.SOURCE, listing.source)
        }
        if (listing.region.lowercase() !in props.allowedRegions.map { it.lowercase() }) {
            return EligibilityVerdict(false, IneligibilityReason.REGION, listing.region)
        }
        val remotePolicy = (listing.remotePolicy ?: "").lowercase()
        if (props.remoteTokens.none { remotePolicy.contains(it) }) {
            return EligibilityVerdict(false, IneligibilityReason.NOT_REMOTE, remotePolicy.ifBlank { "none" })
        }

        val title = listing.roleTitle.lowercase()
        val stackText = listing.stack.joinToString(" ") { it.lowercase() }
        val combined = "$title $stackText"

        props.excludedTitleKeywords.firstOrNull { wordBoundaryContains(title, it) }?.let {
            return EligibilityVerdict(false, IneligibilityReason.EXCLUDED_TITLE, it)
        }
        props.excludedAnyKeywords.firstOrNull { combined.contains(it.lowercase()) }?.let {
            return EligibilityVerdict(false, IneligibilityReason.EXCLUDED_KEYWORD, it)
        }
        if (props.stackAllowlist.none { combined.contains(it.lowercase()) }) {
            return EligibilityVerdict(false, IneligibilityReason.STACK, combined.take(120))
        }
        // Salary floor applies ONLY when a salary is listed (min or max present).
        val floor = java.math.BigDecimal(props.plnB2bFloor)
        val min = listing.salaryMin
        val max = listing.salaryMax
        if (min != null && min < floor) {
            return EligibilityVerdict(false, IneligibilityReason.SALARY_BELOW_FLOOR, "min=$min")
        }
        if (min == null && max != null && max < floor) {
            return EligibilityVerdict(false, IneligibilityReason.SALARY_BELOW_FLOOR, "max=$max")
        }
        return EligibilityVerdict(true)
    }

    /** "head" must not match "backend header"; require a non-letter boundary. */
    private fun wordBoundaryContains(haystack: String, needle: String): Boolean {
        val n = needle.lowercase()
        var index = haystack.indexOf(n)
        while (index >= 0) {
            val beforeOk = index == 0 || !haystack[index - 1].isLetterOrDigit()
            val afterIndex = index + n.length
            val afterOk = afterIndex >= haystack.length || !haystack[afterIndex].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            index = haystack.indexOf(n, index + 1)
        }
        return false
    }
}
