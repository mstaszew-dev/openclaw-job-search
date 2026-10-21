package dev.mstaszew.campaign.worker.apply

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.ConnectException

/**
 * The stats API is an enrichment, never a dependency: any answer that is not a
 * 200 with a parseable payload must yield null so the notifier can post the
 * bare message instead of no message.
 */
private class StubStatsTransport(
    private val status: Int = 200,
    private val body: String = "",
    private val failure: Exception? = null,
) : StatsTransport {
    var requestedUrl: String? = null
        private set

    override fun get(url: String): StatsResponse {
        requestedUrl = url
        failure?.let { throw it }
        return StatsResponse(status, body)
    }
}

/** The exact /api/v1/stats payload shape served by campaign-api. */
private const val API_JSON =
    """{"submitted":1989,"attempted":179,"target":2100,"inFlight":12,"dead":0,"dlqDepth":0,"consumerLag":0,"skippedDuplicate":359,"skippedSalary":16,"skippedFilter":2206,"blockersOpen":0,"remaining":111,"complete":false}"""

class StatsClientTest {

    @Test
    fun `hits the stats endpoint on the configured base url and parses the snapshot`() {
        val t = StubStatsTransport(body = API_JSON)
        val stats = StatsClient("http://campaign-api.campaign.svc.cluster.local:8080", t).fetch()
        assertThat(t.requestedUrl).isEqualTo("http://campaign-api.campaign.svc.cluster.local:8080/api/v1/stats")
        assertThat(stats)
            .isEqualTo(CampaignStatsSnapshot(submitted = 1989, target = 2100, remaining = 111, blockersOpen = 0))
    }

    @Test
    fun `a non-200 answer yields null`() {
        val stats = StatsClient("http://campaign-api", StubStatsTransport(status = 503)).fetch()
        assertThat(stats).isNull()
    }

    @Test
    fun `a transport failure yields null`() {
        val stats =
            StatsClient("http://campaign-api", StubStatsTransport(failure = ConnectException("api down"))).fetch()
        assertThat(stats).isNull()
    }

    @Test
    fun `malformed json yields null`() {
        val stats = StatsClient("http://campaign-api", StubStatsTransport(body = "<html>nope</html>")).fetch()
        assertThat(stats).isNull()
    }

    @Test
    fun `a null-valued field yields null instead of zeros`() {
        val stats = StatsClient("http://campaign-api", StubStatsTransport(body = API_JSON.replace("\"submitted\":1989", "\"submitted\":null"))).fetch()
        assertThat(stats).isNull()
    }

    @Test
    fun `an interrupted fetch yields null and restores the interrupt flag`() {
        val t = StubStatsTransport(failure = InterruptedException("shutting down"))
        val stats = StatsClient("http://campaign-api", t).fetch()
        assertThat(stats).isNull()
        assertThat(Thread.currentThread().isInterrupted).isTrue()
    }
}
