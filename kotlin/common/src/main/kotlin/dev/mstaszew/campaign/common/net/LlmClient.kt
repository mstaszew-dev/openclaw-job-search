package dev.mstaszew.campaign.common.net

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One chat turn for the OpenAI-compatible gateway (msrouter /v1). */
data class ChatMessage(val role: String, val content: String)

data class ChatResult(val content: String, val finishReason: String?)

fun interface ChatTransport {
    fun post(url: String, token: String?, body: String, timeout: Duration): String
}

/** JDK HttpClient transport: reflection-free, native-image friendly. */
class JdkChatTransport(private val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .build(),
) : ChatTransport {
    override fun post(url: String, token: String?, body: String, timeout: Duration): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) {
            throw LlmClientException(
                "gateway returned HTTP ${response.statusCode()}: ${response.body().take(300)}",
                retryable = response.statusCode() == 429 || response.statusCode() >= 500,
            )
        }
        return response.body()
    }
}

class LlmClientException(
    message: String,
    cause: Throwable? = null,
    /** 429/5xx/IO/timeouts are retryable; other 4xx and bad payloads are not. */
    val retryable: Boolean = true,
) : RuntimeException(message, cause)

/**
 * OpenAI-compatible chat client with app-level retries ported from the Python
 * llm.py: 3 attempts, short backoff on rate limit and timeout. No SDK, plain
 * JSON so it survives GraalVM native-image without reflection config.
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val transport: ChatTransport = JdkChatTransport(),
    private val attempts: Int = 3,
    private val retryBackoff: Duration = Duration.ofSeconds(5),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {

    fun chat(
        messages: List<ChatMessage>,
        temperature: Double = 0.2,
        maxTokens: Int = 1500,
        timeout: Duration = Duration.ofSeconds(240),
    ): ChatResult {
        val body = mapper.writeValueAsString(
            mapOf(
                "model" to model,
                "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) },
                "temperature" to temperature,
                "max_tokens" to maxTokens,
            ),
        )
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val raw = transport.post("$baseUrl/chat/completions", apiKey, body, timeout)
                val node: JsonNode = mapper.readTree(raw)
                val choice = node.path("choices").path(0)
                return ChatResult(
                    content = choice.path("message").path("content").asText(""),
                    finishReason = choice.path("finish_reason").asText(null),
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LlmClientException("chat interrupted", e, retryable = false)
            } catch (e: LlmClientException) {
                lastError = e
                if (!e.retryable) throw e
                if (attempt < attempts - 1) sleeper(retryBackoff.multipliedBy(attempt + 1L))
            } catch (e: Exception) {
                lastError = e // IO/timeout class: retryable
                if (attempt < attempts - 1) sleeper(retryBackoff.multipliedBy(attempt + 1L))
            }
        }
        throw LlmClientException("chat failed after $attempts attempts", lastError)
    }
}
