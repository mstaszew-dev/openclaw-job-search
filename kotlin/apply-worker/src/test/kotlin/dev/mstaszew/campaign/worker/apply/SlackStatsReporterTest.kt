package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.SlackClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.scheduling.annotation.Scheduled
import java.net.ConnectException
import java.time.Duration

/**
 * Captures what the reporter would post, so the digest format is asserted on
 * the exact wire text. `failure` simulates a Slack/network outage.
 */
private class CapturingDigestTransport(private val failure: Exception? = null) : ChatTransport {
    var calls = 0
    var lastBody: String? = null

    override fun post(url: String, token: String?, body: String, timeout: Duration): String {
        calls++
        lastBody = body
        failure?.let { throw it }
        return "{\"ok\":true}"
    }
}

/** The exact /api/v1/stats payload shape served by campaign-api (2026-10-21 numbers). */
private const val STATS_JSON =
    """{"submitted":1990,"attempted":179,"target":2100,"inFlight":12,"dead":0,"dlqDepth":0,"consumerLag":0,"skippedDuplicate":359,"skippedSalary":16,"skippedFilter":2206,"blockersOpen":0,"remaining":110,"complete":false}"""

/** null json simulates the stats API being down. */
private fun statsTransport(json: String? = STATS_JSON) = StatsTransport {
    if (json == null) throw ConnectException("stats down")
    StatsResponse(200, json)
}

private fun reporter(transport: ChatTransport, stats: StatsTransport = statsTransport()) =
    SlackStatsReporter(SlackClient("xoxb-test", "#jobcampaign", null, transport), StatsClient("http://campaign-api", stats))

/**
 * Pins the hourly campaign digest posted to #jobcampaign, and the two
 * guarantees that keep it harmless: a stats outage skips the post entirely
 * (the numbers ARE the content, unlike the outcome footer), and a Slack
 * outage never propagates out of a scheduled method.
 */
class SlackStatsReporterTest {

    private val mapper = ObjectMapper()

    private fun postedText(t: CapturingDigestTransport): String =
        mapper.readTree(t.lastBody)["text"].asText()

    @Test
    fun `the digest posts the campaign line`() {
        val t = CapturingDigestTransport()
        reporter(t).report()
        assertThat(postedText(t)).isEqualTo("📊 campaign: 1990/2100 submitted - 110 remaining")
    }

    @Test
    fun `the digest names open blockers when any exist`() {
        val t = CapturingDigestTransport()
        reporter(t, statsTransport(STATS_JSON.replace("\"blockersOpen\":0", "\"blockersOpen\":2"))).report()
        assertThat(postedText(t)).isEqualTo("📊 campaign: 1990/2100 submitted - 110 remaining - 2 blockers open")
    }

    @Test
    fun `a stats outage skips the digest entirely`() {
        val t = CapturingDigestTransport()
        assertDoesNotThrow { reporter(t, statsTransport(json = null)).report() }
        assertThat(t.calls).isZero()
    }

    @Test
    fun `a Slack outage never propagates out of the reporter`() {
        val t = CapturingDigestTransport(failure = ConnectException("slack down"))
        assertDoesNotThrow { reporter(t).report() }
    }

    @Test
    fun `the schedule pins hourly cadence with a 45s first post`() {
        val scheduled = SlackStatsReporter::class.java.getMethod("report").getAnnotation(Scheduled::class.java)
        // The seconds->millis trick ("3600" + "000") is compile-checked nowhere:
        // a dropped concatenation would silently turn the digest into a 1-hour-per-second loop.
        assertThat(scheduled.fixedDelayString).isEqualTo("\${worker.stats-report-interval-seconds:3600}000")
        assertThat(scheduled.initialDelayString).isEqualTo("45000")
    }
}
