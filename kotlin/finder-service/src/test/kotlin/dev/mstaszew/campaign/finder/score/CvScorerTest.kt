package dev.mstaszew.campaign.finder.score

import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.LlmClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** Counts gateway calls, so "paused" can be proven to mean zero spend. */
private class CountingTransport(private val response: String) : ChatTransport {
    var calls = 0
    override fun post(url: String, token: String?, body: String, timeout: Duration): String {
        calls++
        return response
    }
}

private val SCORED_RESPONSE =
    """{"choices":[{"message":{"content":"{\"score\":82,\"reason\":\"Java/Spring match\"}"},"finish_reason":"stop"}]}"""

class CvScorerTest {

    private fun scorer(transport: ChatTransport, enabled: Boolean) = CvScorer(
        llm = LlmClient(
            baseUrl = "http://gateway.invalid/v1",
            apiKey = null,
            model = "mst/free",
            transport = transport,
            attempts = 3,
            retryBackoff = Duration.ZERO,
            sleeper = {},
        ),
        scoringEnabled = enabled,
    )

    private fun score(scorer: CvScorer) = scorer.score(
        company = "Acme",
        roleTitle = "Senior Java Developer",
        stack = listOf("java", "spring"),
        salaryText = "18000-24000 PLN B2B",
        applicantCvProfile = "15 years Java/Kotlin/Spring",
    )

    @Test
    fun `enabled scorer asks the gateway and uses the answer`() {
        val transport = CountingTransport(SCORED_RESPONSE)

        val result = score(scorer(transport, enabled = true))

        assertThat(transport.calls).isEqualTo(1)
        assertThat(result.score).isEqualTo(82)
        assertThat(result.source).isEqualTo(CvScore.Source.LLM)
        assertThat(result.reason).isEqualTo("Java/Spring match")
    }

    @Test
    fun `paused scorer makes no gateway call and returns the fallback score`() {
        val transport = CountingTransport(SCORED_RESPONSE)

        val result = score(scorer(transport, enabled = false))

        assertThat(transport.calls).isZero()
        assertThat(result.score).isEqualTo(70)
        assertThat(result.source).isEqualTo(CvScore.Source.FALLBACK)
        assertThat(result.reason).contains("scoring paused")
    }

    /**
     * Live 2026-11-06/07 DLQ evidence: the free pool's most common failure
     * shape is a TRUNCATED JSON - `{\n  "score": 30,` with no closing brace
     * (maxTokens cut mid-answer). extractJson returned the raw text, readTree
     * died, and a REAL score of 30 was discarded for the neutral 70 - which
     * enqueues low-fit listings at accept level. A readable score inside
     * broken JSON must be salvaged.
     */
    @Test
    fun `truncated json with a readable score is salvaged`() {
        val content = "{\n  \"score\": 30,\n"
        val transport = CountingTransport(envelope(content))

        val result = score(scorer(transport, enabled = true))

        assertThat(result.score).isEqualTo(30)
        assertThat(result.source).isEqualTo(CvScore.Source.LLM)
    }

    /** Prose-wrapped score (model narrates around the JSON) - same salvage. */
    @Test
    fun `prose wrapped score line is salvaged`() {
        val content = "Analysis complete.\n\"score\": 45, \"reason\": \"partial match\". Done."
        val transport = CountingTransport(envelope(content))

        val result = score(scorer(transport, enabled = true))

        assertThat(result.score).isEqualTo(45)
        assertThat(result.source).isEqualTo(CvScore.Source.LLM)
    }

    /** Prose with no score at all keeps today's fallback behavior. */
    @Test
    fun `prose without any score still falls back to 70`() {
        val content = "1. **Analyze the Request:**\n    *   **Goal:** Score a job listing."
        val transport = CountingTransport(envelope(content))

        val result = score(scorer(transport, enabled = true))

        assertThat(result.score).isEqualTo(70)
        assertThat(result.source).isEqualTo(CvScore.Source.FALLBACK)
    }

    /** Review SHOULD 1: an OUT-OF-RANGE quoted match must not salvage. */
    @Test
    fun `salvage rejects out of range quoted score and falls back`() {
        val content = "{\"score\": 250,"
        val transport = CountingTransport(envelope(content))

        val result = score(scorer(transport, enabled = true))

        assertThat(result.score).isEqualTo(70)
        assertThat(result.source).isEqualTo(CvScore.Source.FALLBACK)
    }

    /** Review rename: proves unquoted keys never match the salvage regex. */
    @Test
    fun `unquoted score key is ignored and falls back`() {
        val content = "confidence 999 of 1000; score: 250"
        val transport = CountingTransport(envelope(content))

        val result = score(scorer(transport, enabled = true))

        assertThat(result.score).isEqualTo(70)
        assertThat(result.source).isEqualTo(CvScore.Source.FALLBACK)
    }

    private fun envelope(content: String): String {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        return mapper.writeValueAsString(
            mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content), "finish_reason" to "stop"))),
        )
    }
}
