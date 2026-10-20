package dev.mstaszew.campaign.common.net

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One chat turn for the OpenAI-compatible gateway (msrouter /v1). */
data class ChatMessage(val role: String, val content: String)

/** An OpenAI function-tool schema handed to the gateway with the request. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonNode)

/** One native tool_call parsed out of the gateway response. */
data class LlmToolCall(val name: String, val arguments: JsonNode)

data class ChatResult(
    val content: String,
    val finishReason: String?,
    val toolCalls: List<LlmToolCall> = emptyList(),
)

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
        tools: List<ToolSpec>? = null,
    ): ChatResult {
        val payload = linkedMapOf<String, Any>(
            "model" to model,
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) },
            "temperature" to temperature,
            "max_tokens" to maxTokens,
        )
        // Native tool schemas, the Python agent's contract: with tools on the
        // request the gateway returns proper tool_calls; without them free-tier
        // models improvise tool calls as prose in whatever template they were
        // trained on and nothing can parse them reliably.
        if (!tools.isNullOrEmpty()) {
            payload["tools"] = tools.map {
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to it.name,
                        "description" to it.description,
                        "parameters" to it.parameters,
                    ),
                )
            }
        }
        val body = mapper.writeValueAsString(payload)
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val raw = transport.post("$baseUrl/chat/completions", apiKey, body, timeout)
                val node: JsonNode = mapper.readTree(raw)
                val choice = node.path("choices").path(0)
                return ChatResult(
                    content = choice.path("message").path("content").asText(""),
                    finishReason = choice.path("finish_reason").asText(null),
                    toolCalls = parseToolCalls(choice.path("message").path("tool_calls")),
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

    /**
     * Native tool_calls: [{"id":..,"function":{"name":..,"arguments":"<json
     * string>"}}]. Free-tier gateways sometimes return arguments that are not
     * valid JSON; that degrades to an empty object so the caller sees the tool
     * name and can fail the STEP, not the whole loop with a parse exception.
     */
    private fun parseToolCalls(node: JsonNode): List<LlmToolCall> =
        if (!node.isArray) emptyList()
        else node.map { call ->
            val function = call.path("function")
            val rawArguments = function.path("arguments")
            // Standard wire format is a JSON-encoded string; some free-tier
            // gateways inline the object. Malformed text degrades to an empty
            // object so the caller sees the tool name and can fail the STEP,
            // not the whole loop with a parse exception.
            val arguments = when {
                rawArguments.isObject -> rawArguments
                else -> try {
                    mapper.readTree(rawArguments.asText(""))
                } catch (e: JsonProcessingException) {
                    mapper.createObjectNode()
                }
            }
            LlmToolCall(
                name = function.path("name").asText(""),
                arguments = if (arguments.isObject) arguments else mapper.createObjectNode(),
            )
        }
}
