package dev.mstaszew.campaign.finder.score

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.net.ChatMessage
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.net.LlmClientException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class CvScore(val score: Int, val reason: String, val source: Source) {
    enum class Source { LLM, FALLBACK }
}

/**
 * CV-alignment gate ported from the campaign's in-context LLM reasoning: one
 * chat call per candidate, JSON answer {score, reason}; score >= 60 queues the
 * candidate. Falls back to a neutral accept-with-reason when the gateway is
 * down so a bad LLM day degrades to volume, not to an empty pipeline.
 */
@Component
class CvScorer(
    private val llm: LlmClient,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun score(
        company: String,
        roleTitle: String,
        stack: List<String>,
        salaryText: String?,
        applicantCvProfile: String,
    ): CvScore = try {
        scoreWithLlm(company, roleTitle, stack, salaryText, applicantCvProfile)
    } catch (e: LlmClientException) {
        // Gateway down or output unusable: accept at fallback level so a bad
        // LLM day degrades to volume (fallback tasks are priority-0), never
        // to an empty pipeline.
        log.warn("LLM scoring failed, enqueueing with fallback score: {}", e.message)
        CvScore(70, "fallback (llm unavailable): ${e.message?.take(120)}", CvScore.Source.FALLBACK)
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
        )
        val node = try {
            mapper.readTree(extractJson(result.content))
        } catch (e: Exception) {
            throw LlmClientException("unparseable score output: ${result.content.take(120)}", e)
        }
        val score = node.path("score").asInt(-1)
        if (score < 0 || score > 100) {
            throw LlmClientException("score out of range in: ${result.content.take(120)}")
        }
        return CvScore(score, node.path("reason").asText(""), CvScore.Source.LLM)
    }

    /** Tolerates markdown-fenced JSON, which models like to emit. */
    private fun extractJson(content: String): String {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        return if (start in 0..end) content.substring(start, end + 1) else content
    }

    companion object {
        private val log = LoggerFactory.getLogger(CvScorer::class.java)
    }
}
