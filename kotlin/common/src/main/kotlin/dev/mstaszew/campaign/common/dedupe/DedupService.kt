package dev.mstaszew.campaign.common.dedupe

import dev.mstaszew.campaign.common.domain.SkipReason
import dev.mstaszew.campaign.common.repo.ApplicationRepository
import dev.mstaszew.campaign.common.repo.SkipRepository
import org.springframework.stereotype.Service

/** A job candidate about to be enqueued or applied to. */
data class Candidate(
    val source: String,
    val sourceJobId: String,
    val company: String,
    val companyKey: String? = null,
    val roleTitle: String = "",
    val url: String? = null,
    val region: String = "PL",
) {
    val id: String get() = "$source:$sourceJobId"
}

enum class MatchedOn { ID, COMPANY, URL }

data class DuplicateDecision(
    val duplicate: Boolean,
    val matchedOn: MatchedOn? = null,
    val matchedReference: String? = null,
) {
    companion object {
        val CLEAR = DuplicateDecision(false)
    }
}

/**
 * Ports DEDUPE.md Step 3: duplicate if ANY of id / companyKey (non-sentinel) /
 * normalized URL matches an application or a duplicate skip (which includes
 * auto-generated blockedRepeat rows - they are prior company contacts too).
 * The Gmail 60-day check is a separate, browser-based half executed by the
 * apply worker before submitting.
 */
@Service
class DedupService(
    private val applications: ApplicationRepository,
    private val skips: SkipRepository,
) {

    fun check(candidate: Candidate): DuplicateDecision {
        val companyKey = candidate.companyKey?.takeIf { it.isNotBlank() }
            ?: CompanyKeyNormalizer.normalize(candidate.company)
        val url = UrlNormalizer.normalize(candidate.url)

        // id + url against applications. A candidate with no URL contributes no URL
        // branch: passing "" would match every URL-less application ever stored.
        applications.findFirstByIdOrUrl(candidate.id, url.takeIf { it.isNotBlank() })?.let { app ->
            val matchedOn = if (app.id == candidate.id) MatchedOn.ID else MatchedOn.URL
            return DuplicateDecision(true, matchedOn, app.id)
        }

        // company key (never for sentinel keys or blank keys)
        if (companyKey.isNotBlank() && !CompanyKeyNormalizer.isSentinelKey(companyKey)) {
            applications.findFirstByCompanyKey(companyKey)?.let {
                return DuplicateDecision(true, MatchedOn.COMPANY, it.id)
            }
            skips.findFirstByReasonAndCompanyKey(SkipReason.DUPLICATE, companyKey)?.let {
                return DuplicateDecision(true, MatchedOn.COMPANY, "skip:${it.id}")
            }
        }

        // url + id against duplicate skips
        if (url.isNotBlank()) {
            skips.findFirstByReasonAndUrl(SkipReason.DUPLICATE, url)?.let {
                return DuplicateDecision(true, MatchedOn.URL, "skip:${it.id}")
            }
        }
        skips.findFirstByReasonAndSourceAndSourceJobId(
            SkipReason.DUPLICATE,
            candidate.source,
            candidate.sourceJobId,
        )?.let {
            return DuplicateDecision(true, MatchedOn.ID, "skip:${it.id}")
        }

        return DuplicateDecision.CLEAR
    }
}
