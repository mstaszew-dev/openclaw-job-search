package dev.mstaszew.campaign.common.net

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Captures the request body and replays a canned response, so the wire
 * contract with the gateway is asserted on real JSON, not on a mock's
 * memory of a map.
 */
private class CapturingTransport(private val response: String) : ChatTransport {
    var calls = 0
    var lastBody: String? = null

    override fun post(url: String, token: String?, body: String, timeout: Duration): String {
        calls++
        lastBody = body
        return response
    }
}

private val NAVIGATE_TOOL_CALL_RESPONSE = """
    {"choices":[{"message":{
        "content":"",
        "tool_calls":[{"id":"c1","type":"function","function":{
            "name":"browser_navigate",
            "arguments":"{\"url\":\"https://nofluffjobs.com/pl/job/x\"}"
        }}]
    },"finish_reason":"tool_calls"}]}
""".trimIndent()

class LlmClientTest {

    private fun client(transport: ChatTransport) = LlmClient(
        baseUrl = "http://gateway.invalid/v1",
        apiKey = null,
        model = "mst/free",
        transport = transport,
        attempts = 1,
        retryBackoff = Duration.ZERO,
        sleeper = {},
    )

    private val tools = listOf(
        ToolSpec(
            name = "browser_navigate",
            description = "Navigate the tab",
            parameters = ObjectMapper().readTree("""{"type":"object","properties":{"url":{"type":"string"}}}"""),
        ),
        ToolSpec(name = "browser_snapshot", description = "Read the page", parameters = ObjectMapper().readTree("{}")),
    )

    /**
     * The gateway cannot route tool calls it never hears about. The Python
     * agent passed native tool schemas and got native tool_calls back; this
     * client must do the same or free-tier models improvise tool calls as
     * prose and every apply dies with "model produced no tool call".
     */
    @Test
    fun `chat sends the tool schemas as openai function tools`() {
        val transport = CapturingTransport(NAVIGATE_TOOL_CALL_RESPONSE)

        client(transport).chat(listOf(ChatMessage("user", "go")), tools = tools)

        val body = ObjectMapper().readTree(transport.lastBody)
        val sent = body.path("tools")
        assertThat(sent.isArray).isTrue()
        assertThat(sent.size()).isEqualTo(2)
        assertThat(sent[0].path("type").asText()).isEqualTo("function")
        assertThat(sent[0].path("function").path("name").asText()).isEqualTo("browser_navigate")
        assertThat(sent[0].path("function").path("description").asText()).isEqualTo("Navigate the tab")
        assertThat(sent[0].path("function").path("parameters").path("properties").path("url").isObject).isTrue()
        assertThat(sent[1].path("function").path("name").asText()).isEqualTo("browser_snapshot")
    }

    @Test
    fun `chat surfaces native tool calls from the response`() {
        val transport = CapturingTransport(NAVIGATE_TOOL_CALL_RESPONSE)

        val result = client(transport).chat(listOf(ChatMessage("user", "go")), tools = tools)

        assertThat(result.toolCalls).hasSize(1)
        val call = result.toolCalls.single()
        assertThat(call.name).isEqualTo("browser_navigate")
        assertThat(call.arguments.path("url").asText()).isEqualTo("https://nofluffjobs.com/pl/job/x")
        assertThat(result.finishReason).isEqualTo("tool_calls")
    }

    /** The scoring path sends no tools; the request shape must not change for it. */
    @Test
    fun `chat omits the tools field when none are given`() {
        val transport = CapturingTransport(NAVIGATE_TOOL_CALL_RESPONSE)

        client(transport).chat(listOf(ChatMessage("user", "score me")))

        val body = ObjectMapper().readTree(transport.lastBody)
        assertThat(body.has("tools")).isFalse()
        // Pin the exact key set (and order, as sent): CvScorer byte-compat
        // against gateway drift depends on nothing else sneaking in.
        assertThat(body.fieldNames().asSequence().toList()).containsExactly(
            "model", "messages", "temperature", "max_tokens",
        )
    }

    /** Malformed tool_call arguments must degrade to an empty object, never throw. */
    @Test
    fun `chat tolerates unparseable tool call arguments`() {
        val broken = """
            {"choices":[{"message":{"content":"","tool_calls":[
                {"id":"c1","type":"function","function":{"name":"browser_navigate","arguments":"not json"}}
            ]},"finish_reason":"tool_calls"}]}
        """.trimIndent()
        val transport = CapturingTransport(broken)

        val result = client(transport).chat(listOf(ChatMessage("user", "go")), tools = tools)

        assertThat(result.toolCalls.single().name).isEqualTo("browser_navigate")
        assertThat(result.toolCalls.single().arguments.isObject).isTrue()
        assertThat(result.toolCalls.single().arguments.isEmpty).isTrue()
    }

    /**
     * The OpenAI wire format encodes arguments as a JSON STRING, but some
     * free-tier gateways inline it as an object; both must yield the same
     * parsed arguments.
     */
    @Test
    fun `chat accepts tool call arguments inlined as an object`() {
        val inlined = """
            {"choices":[{"message":{"content":"","tool_calls":[
                {"id":"c1","type":"function","function":{
                    "name":"browser_navigate",
                    "arguments":{"url":"https://x.example/job"}
                }}
            ]},"finish_reason":"tool_calls"}]}
        """.trimIndent()
        val transport = CapturingTransport(inlined)

        val result = client(transport).chat(listOf(ChatMessage("user", "go")), tools = tools)

        assertThat(result.toolCalls.single().arguments.path("url").asText()).isEqualTo("https://x.example/job")
    }
}
