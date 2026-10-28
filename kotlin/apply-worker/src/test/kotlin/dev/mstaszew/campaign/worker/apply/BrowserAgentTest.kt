package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.repo.JobListingRepository
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
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
        cvPathPl = "/app/cv/pl.pdf",
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
            "browser_navigate", "browser_snapshot", "browser_click", "browser_tabs", "browser_fill_form",
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
    fun `parseMaxSteps defaults to 80, clamps below one, and ignores garbage`() {
        assertThat(BrowserAgent.parseMaxSteps(null)).isEqualTo(80)
        assertThat(BrowserAgent.parseMaxSteps("")).isEqualTo(80)
        assertThat(BrowserAgent.parseMaxSteps("  ")).isEqualTo(80)
        assertThat(BrowserAgent.parseMaxSteps("250")).isEqualTo(250)
        assertThat(BrowserAgent.parseMaxSteps(" 80 ")).isEqualTo(80)
        assertThat(BrowserAgent.parseMaxSteps("abc")).isEqualTo(80)
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
    fun `loop breaker aborts after five consecutive failures of the same tool`() {
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws RuntimeException("net::ERR_DEAD")
        val transport = ScriptedTransport(
            listOf(toolCallResponse("browser_navigate", """{"url":"https://x/1"}""")),
        )

        val result = run(agent(transport, maxSteps = 10))

        assertThat(result).isEqualTo(
            ApplyResult.Failed(
                "loop breaker: browser_navigate failed 5 consecutive times; " +
                    "last error: tool browser_navigate failed: net::ERR_DEAD",
            ),
        )
        // 5 model navigations + 1 post-session page blank + 1 tab list whose
        // parse finds no tabs (default mock) = tab hygiene adds 2 calls.
        verify(exactly = 7) { browser.callToolText(any(), any(), any()) }
        verify(exactly = 0) { recorder.recordSubmitted(any(), any(), any()) }
    }

    /**
     * The 2026-10-22 upload_cv deaths were undebuggable: the loop-breaker line
     * cut the combined chooser+CDP error at 150 chars, exactly before the CDP
     * detail. A long last error must keep its tail.
     */
    @Test
    fun `loop breaker keeps the tail of a long last error`() {
        val longError = "tool browser_file_upload failed: " + "x".repeat(400) + " CDP-TAIL-5150"
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws RuntimeException(longError)
        val transport = ScriptedTransport(
            listOf(toolCallResponse("browser_navigate", """{"url":"https://x/1"}""")),
        )

        val result = run(agent(transport, maxSteps = 10))

        assertThat(result).isInstanceOf(ApplyResult.Failed::class.java)
        assertThat((result as ApplyResult.Failed).detail).contains("CDP-TAIL-5150")
    }

    /**
     * Review S1: the worst-case combined upload error runs ~1100 chars and
     * its CDP section starts past char 600, so a 600 cap cuts exactly before
     * the detail this change set exists to expose.
     */
    @Test
    fun `loop breaker keeps the cdp detail of a worst-case combined upload error`() {
        val longError = "tool browser_file_upload failed: " + "x".repeat(700) + " CDP-TAIL-9902"
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws RuntimeException(longError)
        val transport = ScriptedTransport(
            listOf(toolCallResponse("browser_navigate", """{"url":"https://x/1"}""")),
        )

        val result = run(agent(transport, maxSteps = 10))

        assertThat((result as ApplyResult.Failed).detail).contains("CDP-TAIL-9902")
    }

    /**
     * Round-5 revision: the stuck tool's OWN success is the only reset - a
     * DIFFERENT tool's success (a fill_form here, a no-op evaluate there)
     * proves nothing about the stuck action, so the streak must survive it.
     * The round-1..4 any-success-reset rule let interleaved loops grind to
     * the 80-step cap (4 cap-outs on 2026-10-23). ScriptedTransport repeats
     * its last entry; the sequence is fail, fail, ok, fail -> threshold.
     */
    @Test
    fun `a successful different tool between failures leaves the streak alone`() {
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws RuntimeException("net::ERR_DEAD")
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_evaluate", """{"function":"1+1"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
            ),
        )

        val result = run(agent(transport, maxSteps = 7))

        assertThat(result).isEqualTo(
            ApplyResult.Failed(
                "loop breaker: browser_navigate failed 5 consecutive times; " +
                    "last error: tool browser_navigate failed: net::ERR_DEAD",
            ),
        )
    }

    /**
     * 2026-10-23 cap-outs, motivating case: the model snapshots between
     * click retries, so the old any-success-reset rule zeroed the streak
     * every second step and the breaker could never fire - units ground to
     * the 80-step cap with the form fully filled. Under the revised rule any
     * non-failing-tool success leaves the streak alone; this test pins the
     * snapshot flavor of it.
     */
    @Test
    fun `snapshot successes between failures no longer reset the streak`() {
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws RuntimeException("net::ERR_DEAD")
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
            ),
        )

        val result = run(agent(transport, maxSteps = 12))

        assertThat(result).isEqualTo(
            ApplyResult.Failed(
                "loop breaker: browser_navigate failed 5 consecutive times; " +
                    "last error: tool browser_navigate failed: net::ERR_DEAD",
            ),
        )
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
            .startsWith("loop breaker: upload_cv failed 5 consecutive times")
        verify(exactly = 5) { cvUploader.upload(any(), any(), any(), any()) }
    }

    /**
     * Tab hygiene: an attempt leaves its job page open, and job pages carry
     * OOPIF iframes (adzuna/hubspot) that stall every later playwright-mcp
     * connectOverCDP at init (the 2026-10-22 wedge: "initializeServer
     * timeout" with ws connected). Each attempt must blank its tab on the
     * way out.
     */
    @Test
    fun `attempt end blanks the tab so leftover iframes cannot wedge the next spawn`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        verify {
            browser.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                eq(BrowserAgent.PAGE_CLEANUP_TIMEOUT),
            )
        }
    }

    /**
     * Unit-end cleanup, beyond blanking: portals force target=_blank tabs the
     * model never chose (failure mode 7: one dead Lever tab poisoned 5+ later
     * blockers), and widget-laden pages keep OOPIF orphans alive on every tab
     * they touch (failure mode 8: the 2026-10-24 iteamly wedge). Order is
     * load-bearing: blank the CURRENT tab first - its iframes die with the
     * page, and it stays the session's live page as the cleanup survivor;
     * closing it first would strand the MCP session on a dead page reference.
     * Cookie sessions live in the browser profile, not in tabs: closing and
     * blanking cannot log out; only the mode-6 stack wipe drops them.
     */
    private fun stubTabList(vararg lines: String) {
        every {
            browser.callToolText(eq("browser_tabs"), match { it.path("action").asText() == "list" }, any())
        } returns (listOf("### Result") + lines.toList()).joinToString("\n")
    }

    private fun MockKMatcherScope.closeTab(index: Int) =
        match<com.fasterxml.jackson.databind.JsonNode> {
            it.path("action").asText() == "close" && it.path("index").asInt() == index
        }

    @Test
    fun `attempt end closes portal-opened tabs, keeping the blanked survivor`() {
        stubTabList(
            "- 0: (current) [](about:blank)",
            "- 1: [Apply - Senior Dev](https://iteamly.com/apply/x)",
            "- 2: [Job listing](https://nofluffjobs.com/pl/job/x)",
        )
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        verifyOrder {
            browser.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
            browser.callToolText(eq("browser_tabs"), closeTab(2), any())
            browser.callToolText(eq("browser_tabs"), closeTab(1), any())
        }
        verify(exactly = 0) { browser.callToolText(eq("browser_tabs"), closeTab(0), any()) }
    }

    @Test
    fun `the survivor is the session's current tab even when blanking failed`() {
        stubTabList(
            "- 0: (current) [Job listing](https://nofluffjobs.com/pl/job/x)",
            "- 1: [](about:blank)",
        )
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        verify { browser.callToolText(eq("browser_tabs"), closeTab(1), any()) }
        verify(exactly = 0) { browser.callToolText(eq("browser_tabs"), closeTab(0), any()) }
    }

    @Test
    fun `a tab list without a current marker keeps the lowest index`() {
        stubTabList(
            "- 0: [](about:blank)",
            "- 1: [Job listing](https://nofluffjobs.com/pl/job/x)",
        )
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        verify { browser.callToolText(eq("browser_tabs"), closeTab(1), any()) }
        verify(exactly = 0) { browser.callToolText(eq("browser_tabs"), closeTab(0), any()) }
    }

    @Test
    fun `a wedged tab list still blanks the tab and leaves the outcome alone`() {
        every { browser.callToolText(eq("browser_tabs"), any(), any()) } throws
            RuntimeException("initializeServer: Timeout 5000ms exceeded")
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        verify {
            browser.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
        }
    }

    @Test
    fun `one failing tab close does not stop the remaining closes`() {
        stubTabList(
            "- 0: (current) [](about:blank)",
            "- 1: [Apply form](https://iteamly.com/apply/x)",
            "- 2: [Job listing](https://nofluffjobs.com/pl/job/x)",
        )
        every { browser.callToolText(eq("browser_tabs"), closeTab(2), any()) } throws RuntimeException("poof")
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        verify { browser.callToolText(eq("browser_tabs"), closeTab(1), any()) }
        verify {
            browser.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
        }
    }

    @Test
    fun `tab closes are capped so a leaked browser cannot stall the queue tail`() {
        stubTabList(
            "- 0: (current) [](about:blank)",
            "- 1: [t1](https://x.invalid/1)",
            "- 2: [t2](https://x.invalid/2)",
            "- 3: [t3](https://x.invalid/3)",
            "- 4: [t4](https://x.invalid/4)",
            "- 5: [t5](https://x.invalid/5)",
            "- 6: [t6](https://x.invalid/6)",
            "- 7: [t7](https://x.invalid/7)",
            "- 8: [t8](https://x.invalid/8)",
        )
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        verify(exactly = 5) { browser.callToolText(eq("browser_tabs"), match { it.path("action").asText() == "close" }, any()) }
        verify(exactly = 1) { browser.callToolText(eq("browser_tabs"), closeTab(4), any()) }
        verify(exactly = 0) { browser.callToolText(eq("browser_tabs"), closeTab(3), any()) }
    }

    /**
     * The 2026-10-22 drought pathology: free-tier backends emit fill_form
     * fields as an object map {label: value}, playwright-mcp rejects the
     * shape, and the loop breaker kills the attempt after 3 identical zod
     * errors. The coercer must normalize between the model and the MCP
     * server, inferring field types from the last snapshot.
     */
    @Test
    fun `fill_form map-shaped arguments are coerced before the mcp call`() {
        every { browser.callToolText(eq("browser_snapshot"), any(), any()) } returns
            """[f2b3] textbox "Imie i nazwisko"
               |[f6f7] combobox "Forma zatrudnienia"
            """.trimMargin()
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse(
                    "browser_fill_form",
                    """{"fields": {"Forma zatrudnienia": "B2B", "Wynagrodzenie": 15000}}""",
                ),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        verify {
            browser.callToolText(
                eq("browser_fill_form"),
                match { args ->
                    val fields = args.path("fields")
                    fields.isArray &&
                        fields[0].path("name").asText() == "Forma zatrudnienia" &&
                        fields[0].path("type").asText() == "combobox" &&
                        fields[1].path("value").asText() == "15000"
                },
                any(),
            )
        }
    }

    /**
     * The 2026-10-25 justjoin failure chain, pinned in the instructions: the
     * cookie-consent overlay intercepts every pointer event until dismissed,
     * the Apply button opens the EXTERNAL ATS form in a new tab (the old
     * "one tab, never open new tabs" hard rule made those applies
     * structurally impossible), and some portals demand login first - the
     * shared profile is already signed into Google, so that is the only
     * sanctioned login path.
     */
    @Test
    fun `system prompt teaches cookie walls, google login and external-tab applies`() {
        val transport = ScriptedTransport(
            listOf(toolCallResponse("skip_task", """{"reason":"portal-block","detail":"t"}""")),
        )
        run(agent(transport))

        val system = ObjectMapper().readTree(transport.bodies.first())
            .path("messages")
            .filter { it.path("role").asText() == "system" }
            .joinToString("\n") { it.path("content").asText() }
        assertThat(system).contains("Continue with Google")
        assertThat(system).contains("signed into the correct Gmail")
        assertThat(system).contains("before starting the apply")
        assertThat(system).contains("Accept all")
        assertThat(system).contains("browser_tabs")
        assertThat(system).contains("browser_fill_form discipline")
        assertThat(system).contains("never guess labels")
        assertThat(system).contains("declared gone")
        assertThat(system).contains("Never re-read without acting")
        assertThat(system).contains("Just a moment")
        assertThat(system).contains("BEFORE filling the form")
        assertThat(system).contains("no related modal state")
        assertThat(system).contains("continue the form without the CV")
        assertThat(system).contains("element ref from")
        assertThat(system).doesNotContain("never open new tabs")
    }

    @Test
    fun `tool schemas expose tab switching for external ats applies`() {
        assertThat(BrowserAgent.TOOL_SCHEMAS.map { it.name }).contains("browser_tabs")
    }

    /**
     * The 2026-10-26 GET IT TOGETHER cap-out: 109 of 122 steps were
     * browser_snapshot OK - the model re-read the page every ~15s and never
     * assembled the apply sequence. No failure streak tripped because
     * snapshots SUCCEED; the cap was not too low, the model was wandering.
     * A wander breaker ends the unit at WANDER_THRESHOLD consecutive
     * read-only steps (snapshot, tab list, wait) instead of paying the cap.
     */
    /**
     * The pekao-conext stale-ref death (2026-10-27): fill_form failed five
     * consecutive times on "Ref e29 not found in the current page snapshot" -
     * the model retyped the SAME dead ref instead of re-snapshotting. A
     * stale-ref failure must trigger the corrective shove (fresh snapshot
     * ordered), after which a model that re-snapshots completes the apply.
     */
    @Test
    fun `stale-ref failure injects a snapshot correction and the unit recovers`() {
        var fillCalls = 0
        every { browser.callToolText(eq("browser_fill_form"), any(), any()) } answers {
            fillCalls++
            if (fillCalls == 1) {
                throw RuntimeException("Error: Ref e29 not found in the current page snapshot. Try capturing new snapshot.")
            }
            "form filled"
        }
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_fill_form", """{"fields":[]}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_fill_form", """{"fields":[]}"""),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"Application submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        // First request: no correction yet. Some later request: correction
        // text present ("the ref you used" is unique to the shove - the
        // system prompt's own stale-ref rule words it differently).
        assertThat(transport.bodies.first()).doesNotContain("the ref you used")
        assertThat(transport.bodies.last()).contains("the ref you used")
    }

    /**
     * The shove is bounded (STALE_REF_CORRECTION_LIMIT): a model that ignores
     * it still dies at the 5-streak loop breaker, and at most LIMIT
     * corrections were injected into the conversation.
     */
    @Test
    fun `stale-ref corrections are bounded and the loop breaker still fires`() {
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } returns "listing page"
        every { browser.callToolText(eq("browser_fill_form"), any(), any()) } throws
            RuntimeException("Error: Ref e29 not found in the current page snapshot. Try capturing new snapshot.")
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_fill_form", """{"fields":[]}"""),
            ),
        )

        val result = run(agent(transport, maxSteps = 20))

        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("loop breaker: browser_fill_form failed 5")
        val lastBody = transport.bodies.last()
        assertThat(Regex("the ref you used").findAll(lastBody).count())
            .isGreaterThanOrEqualTo(2)
            .isLessThanOrEqualTo(BrowserAgent.STALE_REF_CORRECTION_LIMIT)
    }

    /**
     * Wander breaker tests follow: read-only successes never trip the failure
     * streak, so a separate read-streak breaker is required.
     */
    @Test
    fun `wander breaker aborts repeated identical successful tool calls`() {
        val transport = ScriptedTransport(
            listOf(toolCallResponse("browser_snapshot", "{}")),
        )

        val result = run(agent(transport, maxSteps = 50))

        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("wander breaker: 8 consecutive read-only steps")
    }

    /**
     * The 2026-10-26 sql-rybnik retry: 39 browser_tabs(list) + 30 snapshot
     * alternating - never a click - which evades any per-tool identical
     * counter. Read-only steps must count as a streak WHATEVER their mix;
     * any action tool resets it.
     */
    @Test
    fun `wander breaker catches alternating reads of different tools`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_tabs", """{"action":"list"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_tabs", """{"action":"list"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_tabs", """{"action":"list"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_tabs", """{"action":"list"}"""),
            ),
        )

        val result = run(agent(transport, maxSteps = 50))

        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("wander breaker: 8 consecutive read-only steps")
    }

    /** A different successful tool between repeats resets the wander count. */
    @Test
    fun `an interleaved different tool resets the wander count`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_evaluate", """{"function":"1+1"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_tabs", """{"action":"select","index":1}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("skip_task", """{"reason":"portal-block","detail":"t"}"""),
            ),
        )

        val result = run(agent(transport, maxSteps = 20))

        // Reached the scripted terminal (Blocked via skip_task), not a
        // wander failure: the evaluate and the tab SELECT between the read
        // runs are actions and reset the count.
        assertThat(result).isInstanceOf(ApplyResult.Blocked::class.java)
    }

    /**
     * Cross-tool failure budget: failing DIFFERENT tools in rotation (each
     * below its own per-tool breaker threshold) still burns steps without
     * outcome (justjoin 2026-10-28: step cap 150, zero outcome). The
     * cross-tool budget must end the attempt as Failed instead of grinding
     * to the cap.
     */
    @Test
    fun `cross-tool failure budget ends the unit before the step cap`() {
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws
            RuntimeException("navigate timed out")
        every { browser.callToolText(eq("browser_click"), any(), any()) } throws
            RuntimeException("element not clickable")
        every { browser.callToolText(eq("browser_fill_form"), any(), any()) } throws
            RuntimeException("field not found")
        // All three tools always fail; snapshot never appears in the script.
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_click", """{"target":"f1"}"""),
                toolCallResponse("browser_fill_form", """{"fields":[]}"""),
                toolCallResponse("browser_navigate", """{"url":"https://x/2"}"""),
                toolCallResponse("browser_click", """{"target":"f2"}"""),
                toolCallResponse("browser_fill_form", """{"fields":[]}"""),
                toolCallResponse("browser_navigate", """{"url":"https://x/3"}"""),
                toolCallResponse("browser_click", """{"target":"f3"}"""),
                toolCallResponse("browser_fill_form", """{"fields":[]}"""),
                toolCallResponse("browser_navigate", """{"url":"https://x/4"}"""), // 10th failure
            ),
        )

        val result = run(agent(transport, maxSteps = 20))

        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("failure budget ${BrowserAgent.FAILURE_BUDGET_WITHOUT_SUCCESS} exceeded without any success")
    }

    /** A success resets the cross-tool failure budget so the unit survives. */
    @Test
    fun `a successful call resets the cross-tool failure budget`() {
        every { browser.callToolText(eq("browser_navigate"), any(), any()) } throws
            RuntimeException("navigate timed out")
        every { browser.callToolText(eq("browser_fill_form"), any(), any()) } throws
            RuntimeException("field not found")
        every { browser.callToolText(eq("browser_click"), any(), any()) } returns "clicked"
        val transport = ScriptedTransport(
            listOf(
                // rotate 3 tools: 2 fails + 1 success, repeated; no tool
                // hits 5 consecutive failures, budget resets on each click
                toolCallResponse("browser_navigate", """{"url":"https://x/1"}"""),
                toolCallResponse("browser_fill_form", """{"fields":[]}"""),
                toolCallResponse("browser_click", """{"target":"f1"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://x/2"}"""),
                toolCallResponse("browser_fill_form", """{"fields":[]}"""),
                toolCallResponse("browser_click", """{"target":"f2"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://x/3"}"""),
                toolCallResponse("browser_fill_form", """{"fields":[]}"""),
                toolCallResponse("browser_click", """{"target":"f3"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://x/4"}"""),
                toolCallResponse("browser_fill_form", """{"fields":[]}"""),
                toolCallResponse("browser_click", """{"target":"f4"}"""),
                toolCallResponse("skip_task", """{"reason":"portal-block","detail":"done"}"""),
            ),
        )

        val result = run(agent(transport, maxSteps = 50))

        assertThat(result).isInstanceOf(ApplyResult.Blocked::class.java)
    }

    /**
     * A single degenerate model run should not permanently kill a listing
     * (margo 2026-10-26: 8 reads, wall, listing lost for a wander blip).
     * The FIRST wander trip injects a corrective shove and resets the
     * streak; only the SECOND trip ends the unit.
     */
    @Test
    fun `first wander trip corrects the model, second trip fails`() {
        val transport = ScriptedTransport(
            listOf(
                // trip 1: eight reads
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                // the shove lands; the model wanders again (eight more reads)
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                // then repeats its last entry (snapshot) forever -> trip 2
            ),
        )

        val result = run(agent(transport, maxSteps = 60))

        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("wander breaker: 8 consecutive read-only steps")
        // The correction was actually sent to the model between the trips.
        val allBodies = transport.bodies.joinToString("\n")
        assertThat(allBodies).contains("read-only calls without acting")
    }

    /**
     * The randlab death (2026-10-27): the model narrated ONE prose reply
     * ("I need to take action... a modal appeared") after clicking Apply and
     * the agent failed the unit instantly. Free-tier models do this
     * occasionally; a nudge recovers them. Fail only after NARRATION_LIMIT
     * consecutive tool-less replies.
     */
    @Test
    fun `a narrated reply is nudged back into tool use`() {
        val transport = ScriptedTransport(
            listOf(
                """{"choices":[{"message":{"content":"I need to take action. The Apply button was clicked but the URL has not changed, so a modal appeared."},"finish_reason":"stop"}]}""",
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("skip_task", """{"reason":"portal-block","detail":"t"}"""),
            ),
        )

        val result = run(agent(transport, maxSteps = 10))

        assertThat(result).isInstanceOf(ApplyResult.Blocked::class.java)
        assertThat(transport.bodies.joinToString("\n")).contains("EXACTLY ONE tool now")
    }

    @Test
    fun `narration fails the unit only after the limit`() {
        val prose = """{"choices":[{"message":{"content":"thinking..."},"finish_reason":"stop"}]}"""
        val transport = ScriptedTransport(listOf(prose))

        val result = run(agent(transport, maxSteps = 10))

        assertThat((result as ApplyResult.Failed).detail).startsWith("model produced no tool call after 3 tries")
    }

    /**
     * The german-pracuj death (2026-10-27): the node MCP process died with a
     * V8 abort (exit=134) mid-grind; every later call failed instantly, the
     * failure streak fired, and an infrastructure crash terminally FAILED
     * and walled a perfectly good listing. A dead MCP process must ESCAPE
     * the tool-catch as an exception so Kafka redelivers to a fresh process
     * (mcpFactory spawns a new client per run).
     */
    @Test
    fun `a dead MCP process escapes as a retryable exception, not a failure streak`() {
        every { browser.callToolText(any(), any(), any()) } throws
            RuntimeException("call failed: MCP server process died (exit=134)")
        val transport = ScriptedTransport(
            listOf(toolCallResponse("browser_evaluate", """{"function":"1+1"}""")),
        )

        org.assertj.core.api.Assertions.assertThatThrownBy { run(agent(transport)) }
            .hasMessageContaining("process died")
    }
}
