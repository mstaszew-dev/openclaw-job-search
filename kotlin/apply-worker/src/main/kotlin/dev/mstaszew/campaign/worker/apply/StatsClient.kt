package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** GET result handed to [StatsClient]; the status is checked before parsing. */
data class StatsResponse(val status: Int, val body: String)

fun interface StatsTransport {
    fun get(url: String): StatsResponse
}

/** The slice of /api/v1/stats the Slack footer quotes. */
data class CampaignStatsSnapshot(
    val submitted: Long,
    val target: Long,
    val remaining: Long,
    val blockersOpen: Long,
)

/**
 * Reads the campaign counters from campaign-api so every Slack post can carry
 * a live campaign line. Like Slack itself this is an enrichment, never a
 * dependency: any answer that is not a parseable 200 becomes null, and the
 * caller posts the bare message rather than no message at all.
 */
class StatsClient(
    private val baseUrl: String,
    private val transport: StatsTransport = JdkStatsTransport(),
) {
    private val log = LoggerFactory.getLogger(StatsClient::class.java)
    private val mapper = ObjectMapper()

    fun fetch(): CampaignStatsSnapshot? = try {
        val response = transport.get("$baseUrl/api/v1/stats")
        if (response.status != 200) {
            log.warn("Stats fetch failed: HTTP {}", response.status)
            null
        } else {
            val json = mapper.readTree(response.body)
            // Explicit number checks: a present-but-null field must drop the
            // footer, never render 0/2100 as if the campaign were empty.
            val submitted = json.get("submitted")?.takeIf { it.isNumber }?.asLong() ?: return null
            val target = json.get("target")?.takeIf { it.isNumber }?.asLong() ?: return null
            val remaining = json.get("remaining")?.takeIf { it.isNumber }?.asLong() ?: return null
            val blockersOpen = json.get("blockersOpen")?.takeIf { it.isNumber }?.asLong() ?: return null
            CampaignStatsSnapshot(submitted, target, remaining, blockersOpen)
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt() // shutdown in progress: re-mark, then stand down
        log.warn("Stats fetch interrupted")
        null
    } catch (e: Exception) {
        log.warn("Stats fetch failed: {}", e.toString())
        null
    }
}

/** Default transport: plain JDK GET with a 5s budget, matching SlackClient. */
class JdkStatsTransport : StatsTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun get(url: String): StatsResponse {
        val response = client.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return StatsResponse(response.statusCode(), response.body())
    }
}
