package dev.mstaszew.campaign.finder.score

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.net.ChatMessage
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.net.LlmClientException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

data class CvScore(val score: Int, val reason: String, val source: Source) {
    enum class Source { LLM, FALLBACK }
}

/**
 * CV-alignment gate ported from the campaign's in-context LLM reasoning: one
 * chat call per candidate, JSON answer {score, reason}; score >= 60 queues the
 * candidate. Falls back to a neutral accept-with-reason when the gateway is
 * down so a bad LLM day degrades to volume, not to an empty pipeline.
 *
 * [scoringEnabled] is the operator kill switch: when false the scorer returns
 * the same fallback score without touching the gateway, so a paused gateway
 * spends nothing. Collection and enqueueing continue either way.
 */
class CvScorer(
    private val llm: LlmClient,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val scoringEnabled: Boolean = true,
    /**
     * Per-call gateway timeout. 2026-11-11: one hung scoring call sat on the
     * client default (240s x 3 attempts, 12+ minutes) while the discovery
     * pass held and renewed the browser lease, starving the worker for the
     * whole window. Scoring has a fallback answer; the wait must be bounded.
     */
    internal val requestTimeout: Duration = Duration.ofSeconds(60),
    /**
     * Circuit breaker: after [circuitThreshold] consecutive gateway failures
     * in one discovery pass, stop calling the gateway and serve the fallback
     * score for [circuitOpen]. Keeps a hung gateway from starving the
     * browser-lease pass N x ~3.25 minutes (review S2, 2026-11-11). Auto-closes
     * after [circuitOpen] so a transient gateway blip self-heals.
     */
    private val circuitThreshold: Int = 3,
    private val circuitOpen: Duration = Duration.ofMinutes(5),
) {
    private var consecutiveFailures = 0
    private var circuitOpenUntil: Instant = Instant.MIN

    fun score(
        company: String,
        roleTitle: String,
        stack: List<String>,
        salaryText: String?,
        applicantCvProfile: String,
    ): CvScore =
        if (scoringEnabled) {
            scoreWithFallback(company, roleTitle, stack, salaryText, applicantCvProfile)
        } else {
            CvScore(
                FALLBACK_SCORE,
                "scoring paused (FINDER_SCORING_ENABLED=false): $company / $roleTitle",
                CvScore.Source.FALLBACK,
            )
        }

    private fun scoreWithFallback(
        company: String,
        roleTitle: String,
        stack: List<String>,
        salaryText: String?,
        applicantCvProfile: String,
    ): CvScore = try {
        if (Instant.now() < circuitOpenUntil) {
            return CvScore(FALLBACK_SCORE, "fallback (scoring circuit open)", CvScore.Source.FALLBACK)
        }
        scoreWithLlm(company, roleTitle, stack, salaryText, applicantCvProfile).also { consecutiveFailures = 0 }
    } catch (e: LlmClientException) {
        consecutiveFailures++
        if (consecutiveFailures >= circuitThreshold) {
            circuitOpenUntil = Instant.now().plus(circuitOpen)
        }
        // Gateway down or output unusable: accept at fallback level so a bad
        // LLM day degrades to volume (fallback tasks are priority-0), never
        // to an empty pipeline.
        log.warn("LLM scoring failed, enqueueing with fallback score: {}", e.message)
        CvScore(FALLBACK_SCORE, "fallback (llm unavailable): ${e.message?.take(120)}", CvScore.Source.FALLBACK)
    }

    private fun scoreWithLlm(
        company: String,
        roleTitle: String,
        stack: List<String>,
        salaryText: String?,
        applicantCvProfile: String,
    ): CvScore {
        val system = """
            You score job listings for CV alignment. Answer ONLY with JSON:
            {"score": <0-100 integer>, "reason": "<one short sentence>"}
            Score above 60 means the role matches the CV profile well enough to apply.
            Consider: stack match, role seniority fit, and avoid roles requiring
            skills absent from the profile. Do not consider salary.
        """.trimIndent()
        val user = """
            CV profile:
            $applicantCvProfile

            Listing:
            company: $company
            role: $roleTitle
            stack: ${stack.joinToString(", ")}
            ${salaryText?.let { "salary: $it" } ?: ""}
        """.trimIndent()
        val result = llm.chat(
            listOf(ChatMessage("system", system), ChatMessage("user", user)),
            maxTokens = 200,
            timeout = requestTimeout,
        )
        val content = result.content
        val node = try {
            mapper.readTree(extractJson(content))
        } catch (_: Exception) {
            null
        }
        if (node != null) {
            val score = node.path("score").asInt(-1)
            if (score in 0..100) {
                return CvScore(score, node.path("reason").asText(""), CvScore.Source.LLM)
            }
        }
        // 2026-11-07 DLQ evidence: the free pool's most common shape is a
        // TRUNCATED JSON (`{"score": 30,` cut by maxTokens) - a REAL score
        // was discarded for the neutral 70, enqueuing low-fit listings at
        // accept level. Salvage the quoted "score": N before giving up.
        salvageScore(content)?.let { return it }
        throw LlmClientException("unparseable score output: ${content.take(120)}")
    }

    /** Quoted-key salvage only: `"score": 45` - prose numbers never count. */
    private fun salvageScore(content: String): CvScore? {
        val m = SALVAGE_SCORE_REGEX.find(content) ?: return null
        val value = m.groupValues[1].toIntOrNull() ?: return null
        if (value !in 0..100) return null
        return CvScore(value, "salvaged from unparseable output: ${content.take(100)}", CvScore.Source.LLM)
    }

    /** Tolerates markdown-fenced JSON, which models like to emit. */
    private fun extractJson(content: String): String {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        return if (start in 0..end) content.substring(start, end + 1) else content
    }

    companion object {
        /** Neutral accept used both when the gateway is down and when scoring is paused. */
        const val FALLBACK_SCORE = 70

        /** Quoted-key score salvage (see [salvageScore]); prose numbers never count. */
        private val SALVAGE_SCORE_REGEX = Regex("\"score\"\\s*:\\s*(\\d{1,3})(?!\\d)")
        private val log = LoggerFactory.getLogger(CvScorer::class.java)

        fun logPausedState(enabled: Boolean) {
            if (!enabled) log.warn("CV scoring is paused (FINDER_SCORING_ENABLED=false): every candidate scores {}. No gateway tokens are spent.", FALLBACK_SCORE)
        }
    }
}
