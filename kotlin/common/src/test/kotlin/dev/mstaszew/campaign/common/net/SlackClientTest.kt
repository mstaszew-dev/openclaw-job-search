package dev.mstaszew.campaign.common.net

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Captures the request and replays a canned response, so the wire contract
 * with Slack is asserted on real JSON, not on a mock's memory of a map.
 * `failure` makes the transport simulate an IO/network error.
 */
private class CapturingSlackTransport(
    private val response: String = "{\"ok\":true}",
    private val failure: Exception? = null,
) : ChatTransport {
    var calls = 0
    var lastUrl: String? = null
    var lastToken: String? = null
    var lastBody: String? = null

    override fun post(url: String, token: String?, body: String, timeout: Duration): String {
        calls++
        lastUrl = url
        lastToken = token
        lastBody = body
        failure?.let { throw it }
        return response
    }
}

/**
 * Pins the delivery contract ported from msrouter's director surface
 * (`~/ZCodeProject/msrouter/src/director/surface.ts`): bot token +
 * chat.postMessage first, webhook only when no bot token exists, and no
 * credentials is a permanent silent success — not a retryable failure.
 */
class SlackClientTest {

    private val mapper = ObjectMapper()

    @Test
    fun `bot token posts chat dot postMessage to the configured channel`() {
        val t = CapturingSlackTransport()
        val ok = SlackClient("xoxb-test", "#jobcampaign", null, t).postMessage("hello")
        assertThat(ok).isTrue()
        assertThat(t.calls).isEqualTo(1)
        assertThat(t.lastUrl).isEqualTo("https://slack.com/api/chat.postMessage")
        assertThat(t.lastToken).isEqualTo("xoxb-test")
        assertThat(mapper.readTree(t.lastBody)["channel"].asText()).isEqualTo("#jobcampaign")
        assertThat(mapper.readTree(t.lastBody)["text"].asText()).isEqualTo("hello")
    }

    @Test
    fun `slack ok-false returns false and does not fall back to the webhook`() {
        val t = CapturingSlackTransport(response = "{\"ok\":false,\"error\":\"invalid_auth\"}")
        val ok = SlackClient("xoxb-test", "#jobcampaign", "https://hooks.slack.invalid/T/B/x", t).postMessage("hi")
        assertThat(ok).isFalse()
        assertThat(t.calls).isEqualTo(1)
        assertThat(t.lastUrl).isEqualTo("https://slack.com/api/chat.postMessage")
    }

    @Test
    fun `no credentials is a silent success and never touches the wire`() {
        val t = CapturingSlackTransport()
        val ok = SlackClient(null, null, null, t).postMessage("hi")
        assertThat(ok).isTrue()
        assertThat(t.calls).isEqualTo(0)
    }

    @Test
    fun `bot token without a channel falls back to the webhook like msrouter`() {
        // msrouter: `if (botToken && channel) postMessage else if (webhook) webhook`.
        // A missing channel is a wiring problem, not a reason to drop the
        // event: the webhook is channel-bound already, so it stays correct.
        val t = CapturingSlackTransport()
        val url = "https://hooks.slack.invalid/T/B/x"
        val ok = SlackClient("xoxb-test", null, url, t).postMessage("hi")
        assertThat(ok).isTrue()
        assertThat(t.calls).isEqualTo(1)
        assertThat(t.lastUrl).isEqualTo(url)
        assertThat(t.lastToken).isNull()
    }

    @Test
    fun `token and webhook both absent is silent even with a channel missing`() {
        val t = CapturingSlackTransport()
        val ok = SlackClient("xoxb-test", null, null, t).postMessage("hi")
        assertThat(ok).isTrue()
        assertThat(t.calls).isEqualTo(0)
    }

    @Test
    fun `a transport failure is logged before it is swallowed`() {
        val listAppender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        val logger = org.slf4j.LoggerFactory.getLogger(SlackClient::class.java) as ch.qos.logback.classic.Logger
        listAppender.start()
        logger.addAppender(listAppender)
        try {
            val t = CapturingSlackTransport(failure = java.net.ConnectException("slack unreachable"))
            val ok = SlackClient("xoxb-test", "#jobcampaign", null, t).postMessage("hi")
            assertThat(ok).isFalse()
            assertThat(listAppender.list.any { it.message.contains("Slack post failed") }).isTrue()
        } finally {
            logger.detachAppender(listAppender)
        }
    }

    @Test
    fun `webhook without bot token posts plain text to the webhook url`() {
        val t = CapturingSlackTransport(response = "ok")
        val url = "https://hooks.slack.invalid/T/B/x"
        val ok = SlackClient(null, null, url, t).postMessage("hello")
        assertThat(ok).isTrue()
        assertThat(t.calls).isEqualTo(1)
        assertThat(t.lastUrl).isEqualTo(url)
        assertThat(t.lastToken).isNull()
        assertThat(mapper.readTree(t.lastBody)["text"].asText()).isEqualTo("hello")
    }

    @Test
    fun `transport failure is caught and reported as false`() {
        val t = CapturingSlackTransport(failure = java.net.ConnectException("slack unreachable"))
        val ok = SlackClient("xoxb-test", "#jobcampaign", null, t).postMessage("hi")
        assertThat(ok).isFalse()
    }
}
