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
}
