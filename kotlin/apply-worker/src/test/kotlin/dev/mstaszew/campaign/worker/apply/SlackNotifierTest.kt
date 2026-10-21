package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.SlackClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
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

private fun notifier(transport: ChatTransport) =
    SlackNotifier(SlackClient("xoxb-test", "#jobcampaign", null, transport))

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
}
