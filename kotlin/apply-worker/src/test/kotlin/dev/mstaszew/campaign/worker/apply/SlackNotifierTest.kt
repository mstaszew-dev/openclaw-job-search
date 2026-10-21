package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.SlackClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.net.ConnectException
import java.time.Duration

/**
 * Captures what the notifier would post, so the message formats are asserted
 * on the exact wire text. `failure` simulates a Slack/network outage.
 */
private class CapturingNotifierTransport(private val failure: Exception? = null) : ChatTransport {
    var calls = 0
    var lastBody: String? = null

    override fun post(url: String, token: String?, body: String, timeout: Duration): String {
        calls++
        lastBody = body
        failure?.let { throw it }
        return "{\"ok\":true}"
    }
}

/** The exact /api/v1/stats payload shape served by campaign-api. */
private const val STATS_JSON =
    """{"submitted":1989,"attempted":179,"target":2100,"inFlight":12,"dead":0,"dlqDepth":0,"consumerLag":0,"skippedDuplicate":359,"skippedSalary":16,"skippedFilter":2206,"blockersOpen":0,"remaining":111,"complete":false}"""

/** null json simulates the stats API being down. */
private fun statsTransport(json: String? = null) = StatsTransport {
    if (json == null) throw ConnectException("stats down")
    StatsResponse(200, json)
}

private fun notifier(transport: ChatTransport, stats: StatsTransport = statsTransport()) =
    SlackNotifier(SlackClient("xoxb-test", "#jobcampaign", null, transport), StatsClient("http://campaign-api", stats))

/**
 * Pins the four campaign outcome messages posted to #jobcampaign, and the
 * guarantee that a Slack outage can never break the apply pipeline: the
 * worker's outcome handling must survive the notifier throwing.
 */
class SlackNotifierTest {

    private val mapper = ObjectMapper()

    private fun postedText(t: CapturingNotifierTransport): String =
        mapper.readTree(t.lastBody)["text"].asText()

    @Test
    fun `submission posts the checkmark line with company role and source`() {
        val t = CapturingNotifierTransport()
        notifier(t).submission("UoP", "Fullstack Developer (Java, Angular, LLM)", "nofluffjobs")
        assertThat(postedText(t))
            .isEqualTo("✅ campaign: submitted to UoP - Fullstack Developer (Java, Angular, LLM) (nofluffjobs)")
    }

    @Test
    fun `submission with null company renders a question mark`() {
        val t = CapturingNotifierTransport()
        notifier(t).submission(null, "Kotlin Dev", null)
        assertThat(postedText(t)).isEqualTo("✅ campaign: submitted to ? - Kotlin Dev (?)")
    }

    @Test
    fun `blocked posts the construction line with the reason`() {
        val t = CapturingNotifierTransport()
        notifier(t).blocked("theprotocol:abc123", "captcha wall at step 4")
        assertThat(postedText(t)).isEqualTo("🚧 campaign: blocked theprotocol:abc123 - captcha wall at step 4")
    }

    @Test
    fun `failed posts the warning line with the error`() {
        val t = CapturingNotifierTransport()
        notifier(t).failed("nofluffjobs:xyz", "step cap 25 reached without outcome")
        assertThat(postedText(t)).isEqualTo("⚠️ campaign: apply failed nofluffjobs:xyz - step cap 25 reached without outcome")
    }

    @Test
    fun `deadLettered posts the skull line with the error`() {
        val t = CapturingNotifierTransport()
        notifier(t).deadLettered("nofluffjobs:xyz", "attempt bound burned")
        assertThat(postedText(t)).isEqualTo("💀 campaign: dead-lettered nofluffjobs:xyz - attempt bound burned")
    }

    @Test
    fun `a Slack outage never propagates out of the notifier`() {
        val t = CapturingNotifierTransport(failure = java.net.ConnectException("slack down"))
        assertDoesNotThrow {
            notifier(t).failed("job:1", "boom")
            notifier(t).submission("Co", "Role", "src")
        }
    }

    @Test
    fun `every outcome message carries a campaign stats footer`() {
        val t = CapturingNotifierTransport()
        notifier(t, statsTransport(STATS_JSON)).submission("UoP", "Role", "nofluffjobs")
        assertThat(postedText(t)).isEqualTo(
            "✅ campaign: submitted to UoP - Role (nofluffjobs)\n📊 1989/2100 submitted - 111 remaining",
        )
    }

    @Test
    fun `the footer names open blockers when any exist`() {
        val t = CapturingNotifierTransport()
        notifier(t, statsTransport(STATS_JSON.replace("\"blockersOpen\":0", "\"blockersOpen\":2")))
            .blocked("theprotocol:abc123", "captcha wall at step 4")
        assertThat(postedText(t)).isEqualTo(
            "🚧 campaign: blocked theprotocol:abc123 - captcha wall at step 4" +
                "\n📊 1989/2100 submitted - 111 remaining - 2 blockers open",
        )
    }

    @Test
    fun `a stats outage keeps the bare outcome line`() {
        val t = CapturingNotifierTransport()
        notifier(t, statsTransport(json = null)).deadLettered("nofluffjobs:xyz", "attempt bound burned")
        assertThat(postedText(t)).isEqualTo("💀 campaign: dead-lettered nofluffjobs:xyz - attempt bound burned")
    }
}
