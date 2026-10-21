package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.repo.JobListingRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Optional

/**
 * Replays canned gateway responses in order and keeps every request body, so
 * the test asserts on the actual wire contract, not on a mocked client.
 */
private class ScriptedTransport(private val responses: List<String>) : ChatTransport {
    val bodies = mutableListOf<String>()
    private var i = 0

    override fun post(url: String, token: String?, body: String, timeout: Duration): String {
        bodies += body
        val response = responses[i.coerceAtMost(responses.lastIndex)]
        i++
        return response
    }
}

private fun toolCallResponse(name: String, argumentsJson: String) = """
    {"choices":[{"message":{"content":"","tool_calls":[
        {"id":"c1","type":"function","function":{"name":"$name","arguments":${argumentsJson}}}
    ]},"finish_reason":"tool_calls"}]}
""".trimIndent()

class BrowserAgentTest {

    private val recorder = mockk<Recorder>(relaxed = true)
    private val listings = mockk<JobListingRepository> {
        every { findById("65f0000000000000000000a1") } returns Optional.of(
            JobListingEntity(
                id = "65f0000000000000000000a1",
                source = "nofluffjobs",
                sourceJobId = "n-1",
                company = "Funds-Tech",
                companyKey = "funds-tech",
                roleTitle = "Senior Java Developer",
                url = "https://nofluffjobs.com/pl/job/x",
            ),
        )
    }
    private val cvUploader = mockk<CvUploader>(relaxed = true)
    private val browser = mockk<McpStdioClient>(relaxUnitFun = true) {
        every { callToolText(any(), any(), any()) } returns "page snapshot"
    }
    private val mapper = ObjectMapper()

    private fun agent(transport: ChatTransport, maxSteps: Int = BrowserAgent.EFFECTIVE_MAX_STEPS) = BrowserAgent(
        llm = LlmClient(
            baseUrl = "http://gateway.invalid/v1",
            apiKey = null,
            model = "mst/free",
            transport = transport,
            attempts = 1,
            sleeper = {},
        ),
        recorder = recorder,
        listings = listings,
        cvUploader = cvUploader,
        mapper = mapper,
        maxSteps = maxSteps,
    )

    private fun run(agent: BrowserAgent) = agent.run(
        jobId = "nofluffjobs:n-1",
        listingId = "65f0000000000000000000a1",
        applicantProfileText = "15 years Java",
        cvPathPl = "/cv/pl.pdf",
        mcpFactory = { browser },
    )

    /**
     * The production failure of 2026-10-20: the gateway returns NATIVE
     * tool_calls only when the request carries the tool schemas; without them
     * the model improvises tool calls as prose and every apply died with
     * "model produced no tool call". The agent must send the schemas and act
     * on the native calls.
     */
    @Test
    fun `run sends tool schemas and drives the browser from native tool calls`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse(
                    "browser_navigate",
                    """{"url":"https://nofluffjobs.com/pl/job/x"}""",
                ),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"Application submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        verify {
            browser.callToolText(eq("browser_navigate"), match { it.path("url").asText().endsWith("/job/x") }, any())
        }
        verify(exactly = 1) { recorder.recordSubmitted(any(), eq("nofluffjobs:n-1"), any()) }

        // The wire contract: the first request carries every tool in the
        // system prompt as an OpenAI function schema, with the property types
        // the MCP server actually validates (playwright-mcp rejects stringified
        // fill_form fields and string wait_for time).
        val tools = mapper.readTree(transport.bodies.first()).path("tools")
        assertThat(tools.isArray).isTrue()
        val byName = tools.associate {
            it.path("function").path("name").asText() to it.path("function")
        }
        assertThat(byName.keys).containsExactlyInAnyOrder(
            "browser_navigate", "browser_snapshot", "browser_click", "browser_fill_form",
            "upload_cv", "browser_evaluate", "browser_wait_for",
            "record_submission", "skip_task",
        )
        val fields = byName.getValue("browser_fill_form").path("parameters").path("properties").path("fields")
        assertThat(fields.path("type").asText()).isEqualTo("array")
        assertThat(fields.path("items").path("type").asText()).isEqualTo("object")
        val waitProps = byName.getValue("browser_wait_for").path("parameters").path("properties")
        assertThat(waitProps.path("time").path("type").asText()).isEqualTo("number")
        assertThat(waitProps.path("text").path("type").asText()).isEqualTo("string")
        fun required(tool: String) = byName.getValue(tool).path("parameters").path("required").map { it.asText() }
        assertThat(required("browser_navigate")).containsExactly("url")
        assertThat(required("browser_click")).containsExactly("target")
        assertThat(required("browser_fill_form")).containsExactly("fields")
    }

    /** Backward compatibility: a text-embedded JSON tool call still drives a step. */
    @Test
    fun `a text json tool call still works as a fallback`() {
        val transport = ScriptedTransport(
            listOf(
                """{"choices":[{"message":{"content":"{\"tool\":\"browser_snapshot\",\"arguments\":{}}"}}]}""",
                toolCallResponse("skip_task", """{"reason":"captcha","detail":"widget"}"""),
            ),
        )

        val result = run(agent(transport))

        verify { browser.callToolText(eq("browser_snapshot"), any(), any()) }
        assertThat(result).isInstanceOf(ApplyResult.Blocked::class.java)
    }

    /**
     * The stall guard must report the EFFECTIVE cap (an operator requeueing
     * from Slack reads this number), and the cap must be injectable so tests
     * and the env override share one path.
     */
    @Test
    fun `a non-terminal loop fails with the injected step cap in the message`() {
        val transport = ScriptedTransport(
            listOf(toolCallResponse("browser_navigate", """{"url":"https://x/1"}""")),
        )

        val result = run(agent(transport, maxSteps = 2))

        assertThat(result).isEqualTo(ApplyResult.Failed("step cap 2 reached without outcome"))
    }

    @Test
    fun `parseMaxSteps defaults to 60, clamps below one, and ignores garbage`() {
        assertThat(BrowserAgent.parseMaxSteps(null)).isEqualTo(60)
        assertThat(BrowserAgent.parseMaxSteps("")).isEqualTo(60)
        assertThat(BrowserAgent.parseMaxSteps("  ")).isEqualTo(60)
        assertThat(BrowserAgent.parseMaxSteps("250")).isEqualTo(250)
        assertThat(BrowserAgent.parseMaxSteps(" 60 ")).isEqualTo(60)
        assertThat(BrowserAgent.parseMaxSteps("abc")).isEqualTo(60)
        assertThat(BrowserAgent.parseMaxSteps("0")).isEqualTo(1)
        assertThat(BrowserAgent.parseMaxSteps("-5")).isEqualTo(1)
        // Upper clamp: an absurd cap must keep worstCaseMs + margin inside
        // Int range, because Kafka's poll interval config is Type.INT.
        assertThat(BrowserAgent.parseMaxSteps("999999")).isEqualTo(10000)
    }

    /**
     * The 2026-10-21 cap-outs: a dead listing retried browser_navigate ~50x
     * and a refused CV upload burned all 60 steps. The loop breaker must end
     * the attempt after LOOP_BREAK_THRESHOLD consecutive failures of the SAME
     * tool instead of paying the whole cap for a hopeless loop.
     */
    @Test
    fun `loop breaker aborts after three consecutive failures of the same tool`() {
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws RuntimeException("net::ERR_DEAD")
        val transport = ScriptedTransport(
            listOf(toolCallResponse("browser_navigate", """{"url":"https://x/1"}""")),
        )

        val result = run(agent(transport, maxSteps = 10))

        assertThat(result).isEqualTo(
            ApplyResult.Failed(
                "loop breaker: browser_navigate failed 3 consecutive times; " +
                    "last error: tool browser_navigate failed: net::ERR_DEAD",
            ),
        )
        verify(exactly = 3) { browser.callToolText(any(), any(), any()) }
        verify(exactly = 0) { recorder.recordSubmitted(any(), any(), any()) }
    }

    /**
     * A successful tool call in between (the model adapting by re-snapshotting,
     * the standard recovery move) must reset the streak: only CONSECUTIVE
     * same-tool failures are a loop. ScriptedTransport repeats its last
     * entry, so the sequence here is fail, fail, reset, fail, fail, reset, ...
     * and never reaches the threshold.
     */
    @Test
    fun `a successful different tool between failures resets the streak`() {
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws RuntimeException("net::ERR_DEAD")
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_snapshot", "{}"),
            ),
        )

        val result = run(agent(transport, maxSteps = 7))

        assertThat(result).isEqualTo(ApplyResult.Failed("step cap 7 reached without outcome"))
    }

    /** A success of the failing tool itself is also a reset, not a streak continuation. */
    @Test
    fun `a success of the failing tool resets the streak`() {
        var calls = 0
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } answers {
            calls++
            if (calls % 3 == 0) "landing page" else throw RuntimeException("flaky")
        }
        val transport = ScriptedTransport(
            listOf(toolCallResponse("browser_navigate", """{"url":"https://x/1"}""")),
        )

        val result = run(agent(transport, maxSteps = 7))

        assertThat(result).isEqualTo(ApplyResult.Failed("step cap 7 reached without outcome"))
    }

    /** The refused-upload pathology counts too: upload_cv failures feed the same streak. */
    @Test
    fun `upload_cv failures count toward the loop breaker`() {
        every { cvUploader.upload(any(), any(), any(), any()) } throws RuntimeException("chooser refused")
        val transport = ScriptedTransport(
            listOf(toolCallResponse("upload_cv", """{"selector":"#file"}""")),
        )

        val result = run(agent(transport, maxSteps = 10))

        assertThat(result).isInstanceOf(ApplyResult.Failed::class.java)
        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("loop breaker: upload_cv failed 3 consecutive times")
        verify(exactly = 3) { cvUploader.upload(any(), any(), any(), any()) }
    }
}
