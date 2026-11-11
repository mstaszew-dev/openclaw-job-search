package dev.mstaszew.campaign.finder.score

import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.net.LlmClientException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** Counts gateway calls, so "paused" can be proven to mean zero spend. */
private class CountingTransport(private val response: String) : ChatTransport {
    var calls = 0
    var lastTimeout: Duration? = null
    override fun post(url: String, token: String?, body: String, timeout: Duration): String {
        calls++
        lastTimeout = timeout
        return response
    }
}

/** Fails every call, counting them: the circuit-breaker tests' hung gateway. */
private class FailingTransport : ChatTransport {
    var calls = 0
    override fun post(url: String, token: String?, body: String, timeout: Duration): String {
        calls++
        throw LlmClientException("gateway hung")
    }
}

private val SCORED_RESPONSE =
    """{"choices":[{"message":{"content":"{\"score\":82,\"reason\":\"Java/Spring match\"}"},"finish_reason":"stop"}]}"""

class CvScorerTest {

    private fun scorer(
        transport: ChatTransport,
        enabled: Boolean,
        requestTimeout: Duration = Duration.ofSeconds(60),
        circuitThreshold: Int = 3,
        circuitOpen: Duration = Duration.ofMinutes(5),
    ) = CvScorer(
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
        requestTimeout = requestTimeout,
        circuitThreshold = circuitThreshold,
        circuitOpen = circuitOpen,
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

    /**
     * 2026-11-11 finder wedge: one scoring call hung on the gateway for the
     * client default 240s x 3 attempts (12+ minutes) while the discovery
     * pass held and renewed the browser lease, starving the worker for the
     * whole window. Scoring has a fallback answer; its gateway wait must be
     * bounded well under the client default.
     */
    @Test
    fun `scoring bounds the gateway wait instead of the client 4-minute default`() {
        val transport = CountingTransport(SCORED_RESPONSE)

        score(scorer(transport, enabled = true))

        assertThat(transport.lastTimeout).isEqualTo(Duration.ofSeconds(60))
    }

    /** Review NICE 4: the property-driven path, not only the 60s default. */
    @Test
    fun `non-default scorer timeout reaches the gateway call`() {
        val transport = CountingTransport(SCORED_RESPONSE)

        score(scorer(transport, enabled = true, requestTimeout = Duration.ofSeconds(25)))

        assertThat(transport.lastTimeout).isEqualTo(Duration.ofSeconds(25))
    }

    /**
     * Review S2, 2026-11-11: the 60s per-call bound still leaves N x ~3.25
     * minutes of browser-lease hold when the gateway hangs for a whole
     * discovery pass, because scoring runs inside the lease and LlmClient
     * retries 3x per call. After consecutive failures the scorer must stop
     * calling the gateway at all and serve the fallback score for the rest
     * of the pass.
     */
    @Test
    fun `repeated gateway failures open the circuit and stop calling the gateway`() {
        val transport = FailingTransport()
        val scorer = scorer(transport, enabled = true, circuitThreshold = 2)

        // chat retries 3x per score() call, so 2 score() calls = 6 posts
        repeat(2) { score(scorer) }
        assertThat(transport.calls).isEqualTo(6)

        // circuit now open: the remaining candidates of the pass score
        // without touching the gateway at all
        repeat(3) { score(scorer) }
        assertThat(transport.calls).isEqualTo(6)
    }

    /** The open circuit is a valve, not a switch: one retry after the window. */
    @Test
    fun `circuit reopens after the open window and retries the gateway`() {
        val transport = FailingTransport()
        val scorer = scorer(transport, enabled = true, circuitThreshold = 2, circuitOpen = Duration.ofMillis(30))

        repeat(2) { score(scorer) }
        assertThat(transport.calls).isEqualTo(6)

        Thread.sleep(60)

        score(scorer)
        assertThat(transport.calls).isEqualTo(9)
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
