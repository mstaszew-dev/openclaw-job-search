package dev.mstaszew.campaign.common.net

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration

/**
 * Slack delivery with the semantics ported from msrouter's director surface:
 * bot token + `chat.postMessage` first (routes to the configured channel);
 * the incoming webhook is used only when no bot token exists, because webhook
 * URLs are bound to the channel they were created for. No credentials at all
 * is a permanent silent success — the caller treats it as delivered and moves
 * on. Transport errors are caught and reported as `false`; the caller decides
 * whether that matters. Slack must never break the pipeline that calls it.
 */
class SlackClient(
    private val botToken: String?,
    private val channel: String?,
    private val webhookUrl: String?,
    private val transport: ChatTransport = JdkChatTransport(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    ),
) {
    private val mapper = ObjectMapper()

    fun postMessage(text: String): Boolean = try {
        if (!botToken.isNullOrBlank() && !channel.isNullOrBlank()) {
            val body = mapper.writeValueAsString(mapOf("channel" to channel, "text" to text))
            val response = mapper.readTree(transport.post(CHAT_API, botToken, body, POST_TIMEOUT))
            response["ok"]?.asBoolean() == true
        } else if (!webhookUrl.isNullOrBlank()) {
            val body = mapper.writeValueAsString(mapOf("text" to text))
            transport.post(webhookUrl, null, body, POST_TIMEOUT)
            true
        } else {
            true // no credentials configured: permanent no-op, not a failure
        }
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val CHAT_API = "https://slack.com/api/chat.postMessage"
        val POST_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
