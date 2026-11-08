package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.mcp.McpClientException
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.worker.WorkerProperties
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
    // findById is stubbed typed and empty: createAttemptHistoryEntry reads
    // it for the attempt counter, and a bare relaxed mock answers an
    // untyped Optional whose content casts to Object - each terminal-return
    // test then dies on a ClassCastException instead of its assertion.
    private val jobStateRepo = mockk<JobStateRepository>(relaxed = true) {
        every { findById(any()) } returns Optional.empty()
    }
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

    private fun agent(
        transport: ChatTransport,
        maxSteps: Int = BrowserAgent.EFFECTIVE_MAX_STEPS,
        applyClicker: ApplyClicker = ApplyClicker(),
    ) = BrowserAgent(
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
        jobStateRepository = jobStateRepo,
        cvUploader = cvUploader,
        applyClicker = applyClicker,
        profileFiller = ProfileFiller(WorkerProperties().apply {
            identity = WorkerProperties.Identity().apply {
                name = "Michal"; surname = "Staszewski"; email = "mst.rocking@gmail.com"
                phone = "+48790775407"; city = "Biala Parcela"; country = "Polska"; region = "lodzkie"
            }
        }),
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
            "upload_cv", "apply_click", "open_listing", "profile_fill", "browser_evaluate", "browser_wait_for",
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
        // Upper clamp: the ceiling is DERIVED from the Kafka Type.INT poll
        // bound with the retry-aware step worst case (2026-11-05: a blind
        // 10,000 constant overflowed 2x with the 300s step timeout). Assert
        // the invariant, not a literal that moves with the step timeout.
        assertThat(BrowserAgent.parseMaxSteps("999999"))
            .isEqualTo(dev.mstaszew.campaign.common.messaging.AgentLoopBounds.MAX_STEPS_CEILING)
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

        val result = run(agent(transport, maxSteps = 20))

        assertThat(result).isEqualTo(
            ApplyResult.Failed(
                "loop breaker: browser_navigate failed 10 consecutive times; " +
                    "last error: tool browser_navigate failed: net::ERR_DEAD",
            ),
        )
        // 10 model navigations + 1 post-session page blank + 1 tab list whose
        // parse finds no tabs (default mock) = tab hygiene adds 2 calls.
        verify(exactly = 12) { browser.callToolText(any(), any(), any()) }
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

        val result = run(agent(transport, maxSteps = 20))

        assertThat(result).isEqualTo(
            ApplyResult.Failed(
                "loop breaker: browser_navigate failed 10 consecutive times; " +
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

        val result = run(agent(transport, maxSteps = 20))

        assertThat(result).isEqualTo(
            ApplyResult.Failed(
                "loop breaker: browser_navigate failed 10 consecutive times; " +
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

        val result = run(agent(transport, maxSteps = 20))

        assertThat(result).isInstanceOf(ApplyResult.Failed::class.java)
        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("loop breaker: upload_cv failed 10 consecutive times")
        verify(exactly = 10) { cvUploader.upload(any(), any(), any(), any()) }
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
        // 2026-11-06 theprotocol grind: the drawer has no city field, so
        // "record once all fields are filled" was unsatisfiable and the model
        // looped profile_fill (missing=[city]) to the step cap 15+ times. The
        // rule must make submission reachable with permanent missing fields.
        assertThat(system).contains("record_submission even if some fields stay missing")
        assertThat(system).contains("NEVER call profile_fill twice")
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
            .startsWith("loop breaker: browser_fill_form failed 10")
        val lastBody = transport.bodies.last()
        assertThat(Regex("the ref you used").findAll(lastBody).count())
            .isGreaterThanOrEqualTo(2)
            .isLessThanOrEqualTo(BrowserAgent.STALE_REF_CORRECTION_LIMIT)
    }

    /**
     * Fallback as last resort: when browser_click fails with a stale-ref error
     * and corrections are exhausted, the agent may try browser_evaluate with
     * JavaScript to click via element text - this is a last resort, not the
     * primary path. The model must be prompt-guided to prefer refs from fresh
     * snapshots first.
     */
    @Test
    fun `stale-ref click fallback to browser_evaluate is attempted as last resort`() {
        var clickCalls = 0
        every { browser.callToolText(eq("browser_click"), any(), any()) } answers {
            clickCalls++
            throw RuntimeException("Error: Ref f56e54 not found in the current page snapshot. Try capturing new snapshot.")
        }
        every { browser.callToolText(eq("browser_evaluate"), any(), any()) } returns "clicked via evaluate"
        every { browser.callToolText(eq("browser_snapshot"), any(), any()) } returns """
            [f56e54] button "Aplikuj"
            [f56e55] link "Skip"
        """
        // Enough click responses to exhaust stale-ref corrections (10), then
        // evaluate fallback and submission.
        val clickResponses = List(BrowserAgent.STALE_REF_CORRECTION_LIMIT) {
            toolCallResponse("browser_click", """{"target":"f56e54","element":"Apply button"}""")
        }
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_snapshot", "{}"),
            ) + clickResponses + listOf(
                toolCallResponse("browser_evaluate", """{"function":"document.querySelector('button').click()"}"""),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://nfj.com/applied","confirmationText":"Application submitted","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport, maxSteps = 50))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        // Verify fallback was attempted (click failed at least LIMIT times)
        assertThat(clickCalls).isGreaterThanOrEqualTo(BrowserAgent.STALE_REF_CORRECTION_LIMIT)
    }

    /**
     * System prompt teaches proactive click recovery: when a click fails with
     * "not found in the current page snapshot", the model must re-snapshot
     * and use fresh refs - never retype a dead ref.
     */
    @Test
    fun `system prompt teaches proactive click recovery`() {
        val transport = ScriptedTransport(
            listOf(toolCallResponse("skip_task", """{"reason":"portal-block","detail":"t"}""")),
        )
        run(agent(transport))

        val system = ObjectMapper().readTree(transport.bodies.first())
            .path("messages")
            .filter { it.path("role").asText() == "system" }
            .joinToString("\n") { it.path("content").asText() }
        assertThat(system).contains("not found in the current page snapshot")
        assertThat(system).contains("fresh snapshot")
        assertThat(system).contains("dead ref")
    }

    /**
     * System prompt suggests browser_evaluate as a last resort for clicks
     * when refs keep going stale.
     */
    @Test
    fun `system prompt mentions evaluate as last resort for clicks`() {
        val transport = ScriptedTransport(
            listOf(toolCallResponse("skip_task", """{"reason":"portal-block","detail":"t"}""")),
        )
        run(agent(transport))

        val system = ObjectMapper().readTree(transport.bodies.first())
            .path("messages")
            .filter { it.path("role").asText() == "system" }
            .joinToString("\n") { it.path("content").asText() }
        assertThat(system).contains("browser_evaluate")
        assertThat(system).contains("last resort")
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

        // 20 reads -> trip 1 (correction), next read -> trip 2 immediately.
        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("wander breaker:")
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

        // 20 alternating reads -> trip 1 (correction), next read -> trip 2 immediately.
        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("wander breaker:")
    }

    /**
     * The 2026-10-30 theprotocol PHP/Symfony cap-out exploit: AFTER the
     * first wander trip's correction, the model kept grinding with
     * browser_tabs(SELECT) and browser_evaluate interleaved into snapshot
     * waves - pseudo-actions that reset readStreak, so the second (terminal)
     * trip never fired and the unit rode to cap 150 with zero clicks or
     * fills. Once tightened (wanderTrips >= 1), evaluate and ANY tab call
     * count as read-only and the second wave dies at the breaker, far below
     * the step cap.
     */
    @Test
    fun `after a wander trip, pseudo-actions no longer reset the streak`() {
        // trip 1: the snapshot wave + the interleaved evaluate/tab-select
        // reset trick reach the 20-step streak, the correction is injected
        // and readStreak resets; the model relapses with the SAME trick.
        val transports = buildList {
            repeat(19) { add(toolCallResponse("browser_snapshot", "{}")) }
            add(toolCallResponse("browser_evaluate", """{"function":"1+1"}"""))
            repeat(19) { add(toolCallResponse("browser_snapshot", "{}")) }
            add(toolCallResponse("browser_tabs", """{"action":"select","index":1}"""))
            repeat(19) { add(toolCallResponse("browser_snapshot", "{}")) }
        }

        val result = run(agent(ScriptedTransport(transports), maxSteps = 150))

        // Trip 1 at step 20 (correction injected); trip 2 fires immediately
        // on the next read-only call - the model ignored the shove.
        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("wander breaker:")
    }

    /**
     * NFJ 2026-11-04 (senior-ux-ui-designer-bluebinary): the model navigated
     * to the SAME listing URL 15 times in a row and rode to the 200-step cap.
     * Every navigate counts as an action, so the wander breaker never fired.
     * A consecutive identical navigate must be blocked deterministically
     * (never reaching MCP) with a corrective shove, and the loop must
     * continue afterward.
     */
    @Test
    fun `repeated identical navigates are blocked and never reach the browser`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/success","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        // Only the FIRST navigate executed; repeats 2 and 3 were blocked
        // before dispatch, and the corrective text reached the model.
        // (TabCleanup's about:blank post-session navigate is not a model call.)
        verify(exactly = 1) {
            browser.callToolText(eq("browser_navigate"), match { it.path("url").asText() != "about:blank" }, any())
        }
        assertThat(transport.bodies[2]).contains("BLOCKED")
        assertThat(transport.bodies[2]).contains("same URL")
        // Repeat 3 was blocked too (streak kept growing), pinning that a
        // relapsing model stays blocked rather than oscillating ok/blocked.
        assertThat(transport.bodies[3]).contains("BLOCKED")
    }

    /**
     * 2026-11-05 specjalista DLQ: the model relapsed into 10 same-URL
     * navigates, the flat BLOCKED text repeated 10 times did not move it, and
     * the listing dead-lettered after exhausting maxAttempts. From the 3rd
     * block the guard escalates to a decisive recipe instead of the flat text.
     */
    @Test
    fun `navigate guard escalates to a decisive recipe after repeated blocks`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("skip_task", """{"reason":"duplicate","detail":"t"}"""),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.DuplicateNow::class.java)
        // 2nd block (body index 2): the flat text. 3rd block (body 3): escalated.
        assertThat(transport.bodies[2]).contains("re-navigating reloads the page")
        assertThat(transport.bodies[2]).doesNotContain("Do exactly this")
        assertThat(transport.bodies[3]).contains("Do exactly this")
        assertThat(transport.bodies[3]).contains("profile_fill")
    }

    /** A different URL between repeats is a legitimate navigation, not a loop. */
    @Test
    fun `a different navigate URL resets the repeat guard`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://justjoin.it/job/y"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("skip_task", """{"reason":"captcha","detail":"t"}"""),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Blocked::class.java)
        // X, Y, X dispatched; the 4th (X again, after the URL change reset the
        // guard) is blocked as the 2nd consecutive identical navigate.
        verify(exactly = 3) {
            browser.callToolText(eq("browser_navigate"), match { it.path("url").asText() != "about:blank" }, any())
        }
    }

    /**
     * A FAILED navigate earns one retry (the NFJ 403 rate-limit: navigation
     * intermittently dies with net::ERR_HTTP_RESPONSE_CODE_FAILURE, and a
     * same-URL retry is the correct response). The guard must stay dormant
     * while the last identical navigate failed.
     */
    @Test
    fun `a failed navigate earns a same-url retry before any block`() {
        var navCalls = 0
        every {
            browser.callToolText(eq("browser_navigate"), match { it.path("url").asText() != "about:blank" }, any())
        } answers {
            navCalls++
            if (navCalls == 1) throw RuntimeException("net::ERR_HTTP_RESPONSE_CODE_FAILURE")
            "page loaded"
        }
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}"""),
                toolCallResponse("skip_task", """{"reason":"captcha","detail":"t"}"""),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Blocked::class.java)
        verify(exactly = 2) {
            browser.callToolText(eq("browser_navigate"), match { it.path("url").asText() != "about:blank" }, any())
        }
    }

    /**
     * A model that relapses after the shove dies on the ordinary failure
     * streak: every blocked navigate is a browser_navigate failure, so the
     * loop breaker ends the unit far below the step cap instead of letting
     * the navigate grind ride to 200 steps.
     */
    @Test
    fun `relapsing into identical navigates hits the loop breaker`() {
        val transport = ScriptedTransport(
            List(12) { toolCallResponse("browser_navigate", """{"url":"https://nofluffjobs.com/pl/job/x"}""") },
        )

        val result = run(agent(transport, maxSteps = 50))

        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("loop breaker: browser_navigate failed 10 consecutive times")
    }

    /**
     * apply_click is dispatched worker-side like upload_cv (NFJ 2026-11-04:
     * the model cannot emit reliable click refs). Its Error verdicts must
     * feed the ordinary same-tool failure streak, and a success must let the
     * run continue to the ledger.
     */
    @Test
    fun `relapsing apply_click failures hit the loop breaker`() {
        val failingClicker = mockk<ApplyClicker> {
            every { click(any(), any()) } returns "Error: apply_click: no apply control found on this page"
        }
        val transport = ScriptedTransport(
            List(12) { toolCallResponse("apply_click", "{}") },
        )

        val result = run(agent(transport, maxSteps = 50, applyClicker = failingClicker))

        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("loop breaker: apply_click failed 10 consecutive times")
    }

    @Test
    fun `a successful apply_click continues the run to the ledger`() {
        val clicker = mockk<ApplyClicker> {
            every { click(any(), any()) } returns
                "apply_click: clicked \"Aplikuj\"; URL after click: https://nofluffjobs.com/pl/job/x/success.\n" +
                "Tabs now:\n- 1: [Traffit](https://x.traffit.com/form)"
        }
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("apply_click", "{}"),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x.traffit.com/thanks","confirmationText":"ok","ats":"traffit"}""",
                ),
            ),
        )

        val result = run(agent(transport, applyClicker = clicker))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        io.mockk.verify(exactly = 1) { clicker.click(any(), null) }
    }

    /**
     * 2026-11-05: 6 of 8 recent failures were the model emitting garbage
     * URLs ("https://: ", "https://}}") on browser_navigate - it mangles
     * even the listing URL it is told verbatim. open_listing must navigate
     * to the LISTING url deterministically, ignoring any model argument,
     * while riding the same navigate path (guard + breakers).
     */
    @Test
    fun `open_listing navigates to the listing url ignoring model arguments`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("open_listing", """{"url":"https://garbage.example/}"}"""),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/ok","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        verify(exactly = 1) {
            browser.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "https://nofluffjobs.com/pl/job/x" },
                any(),
            )
        }
    }

    /**
     * 2026-11-05 wordpress regrind death: the browser was already ON the
     * listing URL, but open_listing navigated anyway, tripped the identical
     * navigate guard ("BLOCKED: this is navigate #N in a row to the exact
     * same URL"), and the model's retries rode the loop breaker to an
     * attempt kill (170 steps, 20 open_listing failures). Already being on
     * the listing is the goal state: open_listing must answer success with
     * zero navigation.
     */
    @Test
    fun `open_listing answers success when already on the listing page`() {
        every {
            browser.callToolText(eq("browser_evaluate"), any(), any())
        } returns "https://nofluffjobs.com/pl/job/x"

        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("open_listing", "{}"),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/ok","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        // Cleanup (TabCleanup) navigates to about:blank after every run, so
        // the assertion is scoped to the listing URL: open_listing itself
        // must not navigate there.
        verify(exactly = 0) {
            browser.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "https://nofluffjobs.com/pl/job/x" },
                any(),
            )
        }
        // The idempotence check reads the live location worker-side.
        verify {
            browser.callToolText(
                eq("browser_evaluate"),
                match { it.path("function").asText() == "location.href" },
                any(),
            )
        }
    }

    /**
     * The wordpress death shape, review BLOCKER-2 (2026-11-06): the old
     * contract corrective-FAILED the 2nd no-op open_listing, which fed the
     * loop breaker, matched MODEL_FLAKE_SIGNATURES ("BLOCKED: open_listing"),
     * requeued the job, and the next attempt ground identically - 13
     * theprotocol listings burned 11-26 open_listing calls per attempt for
     * zero progress. Two relapses prove the model cannot act on this page
     * state: the attempt must END immediately. With regrind budget left the
     * terminal result is Transient (requeue; a retry reads the attempt
     * history).
     */
    @Test
    fun `second open_listing on the same page ends the attempt as a requeue`() {
        every {
            browser.callToolText(eq("browser_evaluate"), any(), any())
        } returns "https://nofluffjobs.com/pl/job/x"

        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("open_listing", "{}"),
                toolCallResponse("open_listing", "{}"),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Transient::class.java)
        assertThat((result as ApplyResult.Transient).detail).contains("open_listing relapse")
    }

    /**
     * Live 2026-11-06 (ai-automation-specialist swidnik): the model called
     * profile_fill 5 times consecutively (missing=[city] every time) and,
     * because profile_fill counts as an ACTION, each call reset the wander
     * breaker - the unit rode to the 200-step cap. profile_fill's answer
     * NEVER changes on the same form, so two consecutive identical answers
     * prove the model will not act: the attempt must end, regrind-aware
     * exactly like the open_listing relapse.
     */
    @Test
    fun `repeated identical profile_fill answers end the attempt as a requeue`() {
        every { browser.callToolText(eq("browser_evaluate"), any(), any()) } returns "https://nofluffjobs.com/pl/job/x"
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("open_listing", "{}"),
                toolCallResponse("profile_fill", "{}"),
                toolCallResponse("profile_fill", "{}"),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Transient::class.java)
        assertThat((result as ApplyResult.Transient).detail).contains("profile_fill repeat")
    }

    /**
     * 2026-11-08 iteration: the repeat call itself is the signal the model
     * will not advance - but on theprotocol the drawer AUTO-SUBMITS after
     * profile_fill (live evidence: confirmation text recorded straight after
     * a fill), so the page already shows the thank-you the model failed to
     * read. On the repeat, the worker detects the confirmation worker-side
     * and hands the model the exact record_submission arguments instead of
     * killing the attempt.
     */
    @Test
    fun `profile_fill repeat with a visible confirmation hands the evidence to the model`() {
        every { browser.callToolText(eq("browser_evaluate"), any(), any()) } answers {
            val fn = secondArg<com.fasterxml.jackson.databind.JsonNode>().path("function").asText()
            if (fn.contains(ProfileFiller.CONFIRM_MARKER)) {
                ProfileFiller.CONFIRM_MARKER + "{\"confirmed\":true,\"marker\":\"dziękujemy\",\"url\":\"https://theprotocol.it/success\"}"
            } else {
                "https://nofluffjobs.com/pl/job/x"
            }
        }
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("open_listing", "{}"),
                toolCallResponse("profile_fill", "{}"),
                toolCallResponse("profile_fill", "{}"),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://theprotocol.it/success","confirmationText":"dziękujemy","ats":"theprotocol"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
    }

    /** The guidance text must carry the exact evidence the model should record. */
    @Test
    fun `the confirmation guidance names the confirmation text and url`() {
        every { browser.callToolText(eq("browser_evaluate"), any(), any()) } answers {
            val fn = secondArg<com.fasterxml.jackson.databind.JsonNode>().path("function").asText()
            if (fn.contains(ProfileFiller.CONFIRM_MARKER)) {
                ProfileFiller.CONFIRM_MARKER + "{\"confirmed\":true,\"marker\":\"przesłane\",\"url\":\"https://x/thanks\"}"
            } else {
                "https://nofluffjobs.com/pl/job/x"
            }
        }
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("open_listing", "{}"),
                toolCallResponse("profile_fill", "{}"),
                toolCallResponse("profile_fill", "{}"),
            ),
        )

        run(agent(transport, maxSteps = 5))

        assertThat(transport.bodies.last()).contains("record_submission NOW")
        assertThat(transport.bodies.last()).contains("przesłane")
        assertThat(transport.bodies.last()).contains("https://x/thanks")
    }

    /**
     * 2026-11-08 sopot: a form where the drawer does NOT auto-submit - the
     * confirmation probe found nothing, so the repeat terminal killed an
     * otherwise-filled apply. The form was open with a submit control; a
     * human would click it. The worker does the same: on the repeat, when no
     * confirmation is visible, probe for the submit control, click ONCE
     * (worker-side, zero model JS), re-probe the confirmation, and hand the
     * model the evidence.
     */
    @Test
    fun `profile_fill repeat with an open form clicks submit once and reports the confirmation`() {
        every { browser.callToolText(eq("browser_evaluate"), any(), any()) } answers {
            val fn = secondArg<com.fasterxml.jackson.databind.JsonNode>().path("function").asText()
            when {
                fn.contains(ProfileFiller.CONFIRM_MARKER) ->
                    ProfileFiller.CONFIRM_MARKER + "{\"confirmed\":false,\"marker\":\"\",\"url\":\"https://theprotocol.it/aplikuj\"}"
                fn.contains(ProfileFiller.SUBMIT_MARKER) ->
                    ProfileFiller.SUBMIT_MARKER + "{\"inputs\":3,\"found\":true,\"text\":\"Wyślij aplikację\"}"
                fn.contains(ProfileFiller.CLICK_MARKER) ->
                    ProfileFiller.CLICK_MARKER + "{\"clicked\":true,\"text\":\"Wyślij aplikację\"}"
                else -> "https://nofluffjobs.com/pl/job/x"
            }
        }
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("open_listing", "{}"),
                toolCallResponse("profile_fill", "{}"),
                toolCallResponse("profile_fill", "{}"),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://theprotocol.it/x","confirmationText":"done","ats":"theprotocol"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        // The click-path guidance names the button it clicked for the model.
        assertThat(transport.bodies.last()).contains("AUTO-SUBMIT clicked")
        assertThat(transport.bodies.last()).contains("Wyślij aplikację")
    }

    /** The assist clicks ONCE per attempt: a second repeat with the assist
     * already used takes the relapse terminal (no double-submit risk). */
    @Test
    fun `a second submit assist in one attempt is refused`() {
        every { browser.callToolText(eq("browser_evaluate"), any(), any()) } answers {
            val fn = secondArg<com.fasterxml.jackson.databind.JsonNode>().path("function").asText()
            when {
                fn.contains(ProfileFiller.CONFIRM_MARKER) ->
                    ProfileFiller.CONFIRM_MARKER + "{\"confirmed\":false,\"marker\":\"\",\"url\":\"https://x\"}"
                fn.contains(ProfileFiller.SUBMIT_MARKER) ->
                    ProfileFiller.SUBMIT_MARKER + "{\"inputs\":3,\"found\":true,\"text\":\"Wyślij\"}"
                fn.contains(ProfileFiller.CLICK_MARKER) ->
                    ProfileFiller.CLICK_MARKER + "{\"clicked\":true,\"text\":\"Wyślij\"}"
                else -> "https://nofluffjobs.com/pl/job/x"
            }
        }
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("open_listing", "{}"),
                toolCallResponse("profile_fill", "{}"),
                toolCallResponse("profile_fill", "{}"),
                toolCallResponse("profile_fill", "{}"),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Transient::class.java)
        assertThat((result as ApplyResult.Transient).detail).contains("profile_fill repeat")
    }

    /**
     * Convergence (review BLOCKER-2): requeueing alone cannot converge on a
     * deterministic relapse. Once the regrind budget is spent (regrindCycles
     * at the cap) the relapse must WALL: a Failed whose detail avoids every
     * MODEL_FLAKE_SIGNATURES string, so wallFailedReenqueue takes the
     * listing out of the queue with a ledger decision.
     */
    @Test
    fun `open_listing relapse walls the listing once regrind is exhausted`() {
        every { jobStateRepo.findById(any()) } returns Optional.of(
            dev.mstaszew.campaign.common.domain.JobStateDocument(
                id = "nofluffjobs:n-1",
                regrindCycles = dev.mstaszew.campaign.common.repo.JobStateRepositoryImpl.MAX_REGRIND_CYCLES,
            ),
        )
        every {
            browser.callToolText(eq("browser_evaluate"), any(), any())
        } returns "https://nofluffjobs.com/pl/job/x"

        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("open_listing", "{}"),
                toolCallResponse("open_listing", "{}"),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Failed::class.java)
        val detail = (result as ApplyResult.Failed).detail
        assertThat(detail).contains("open_listing relapse")
        // The wall wire-contract: the detail must carry NO model-flake
        // signature, or WorkerLoop would requeue instead of walling and the
        // grind would cycle forever (evolve together).
        dev.mstaszew.campaign.worker.WorkerLoop.MODEL_FLAKE_SIGNATURES.forEach {
            assertThat(detail).doesNotContain(it)
        }
    }

    /**
     * The location probe sits IN FRONT of the infra escape (review
     * 2026-11-05): swallowing a dead-MCP exception would fall through to a
     * doomed navigate and lose the first-detection Transient path
     * (german-pracuj). Death must propagate out of the probe: Transient,
     * attempt history, and zero navigation.
     */
    @Test
    fun `open_listing probe death returns Transient without navigating`() {
        every {
            browser.callToolText(eq("browser_evaluate"), any(), any())
        } throws McpClientException("call failed: MCP server process died (exit=134)")

        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("open_listing", "{}"),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/ok","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Transient::class.java)
        assertThat((result as ApplyResult.Transient).detail).startsWith("MCP server process died")
        verify(exactly = 0) {
            browser.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "https://nofluffjobs.com/pl/job/x" },
                any(),
            )
        }
        verify { recorder.recordAttemptHistory(any(), match { it.finalStatus.contains("process died") }) }
    }

    /**
     * NFJ recipe + success shape (2026-11-04): the Aplikuj button is marked
     * [disabled] in the snapshot until the SPA hydrates, and a working click
     * opens the ATS in a NEW TAB while the NFJ tab lands on /success - the
     * model kept clicking a dead button and navigating back because nothing
     * taught it these two facts. The recipe must need NO JavaScript: the
     * free-tier model cannot compose a syntactically valid evaluate call
     * (2026-11-04 21:56 run: 10 consecutive SyntaxErrors on a taught
     * ternary), so the check reads the [disabled] snapshot marker instead.
     */
    @Test
    fun `system prompt teaches the NFJ hydration gate and success shape`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/success","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )

        run(agent(transport))

        val system = ObjectMapper().readTree(transport.bodies.first())
            .path("messages")
            .filter { it.path("role").asText() == "system" }
            .joinToString("\n") { it.path("content").asText() }
        assertThat(system).contains("[disabled]")
        assertThat(system).contains("wait_for(time=3)")
        assertThat(system).contains("/success")
        assertThat(system).contains("Zaznacz")
        // 2026-11-06 business-analyst: the quick-apply form was SUBMITTED (the
        // thank-you tracker modal appeared) but the model ground the tracker
        // buttons and never called record_submission - the ledger missed a
        // real application. The prompt must teach: confirmation modal = record
        // immediately, tracker buttons are optional.
        assertThat(system).contains("QUICK-APPLY confirmation")
            .contains("record_submission IMMEDIATELY")
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
     * A single degenerate model run should not permanently kill a listing
     * (margo 2026-10-26: 8 reads, wall, listing lost for a wander blip).
     * The FIRST wander trip injects a corrective shove and resets the
     * streak; only the SECOND trip ends the unit.
     */
    @Test
    fun `first wander trip corrects the model, second trip fails`() {
        val transport = ScriptedTransport(
            listOf(
                // trip 1 fires at WANDER_THRESHOLD (20) reads; ScriptedTransport
                // clamps to its last entry after the list is exhausted, so the
                // "eight reads" comments below describe the pattern, not the count
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                // the shove lands; the model wanders again with reads
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
            .startsWith("wander breaker:")
        // The correction was actually sent to the model between the trips.
        val allBodies = transport.bodies.joinToString("\n")
        assertThat(allBodies).contains("read-only calls without acting")
    }

    /**
     * After trip 1 injects WANDER_CORRECTION, the model's next read-only call
     * (ignoring the shove) must trip the breaker immediately - not grant
     * another full 20-read grace period. This is the 2026-10-31 theprotocol
     * pattern: 17 reads, trip 1, one click, then reads again to cap 150.
     */
    @Test
    fun `wander trip 2 fires immediately after correction is ignored`() {
        val transport = ScriptedTransport(buildList {
            repeat(20) { add(toolCallResponse("browser_snapshot", "{}")) }
            // model ignores the shove and reads again -> trip 2 immediately
            add(toolCallResponse("browser_snapshot", "{}"))
        })

        val result = run(agent(transport, maxSteps = 150))

        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("wander breaker:")
        // The correction was actually sent to the model between the trips.
        val allBodies = transport.bodies.joinToString("\n")
        assertThat(allBodies).contains("read-only calls without acting")
        // Trip 2 detail names the correction that was ignored.
        assertThat((result as ApplyResult.Failed).detail).contains("ignored")
    }

    /**
     * If the model heeds the correction (takes an action after trip 1),
     * wanderCorrectionPending clears and the normal 20-read threshold applies
     * to any subsequent wander. The model gets one warning per trip, not one
     * per attempt.
     */
    @Test
    fun `wander correction heeded resumes normal wander threshold`() {
        val transport = ScriptedTransport(buildList {
            repeat(20) { add(toolCallResponse("browser_snapshot", "{}")) }
            // model heeds the shove: an action clears the pending correction
            add(toolCallResponse("browser_click", """{"ref":"apply-btn"}"""))
            // model wanders again: 20 reads -> trip 2 at normal threshold
            repeat(20) { add(toolCallResponse("browser_snapshot", "{}")) }
        })

        val result = run(agent(transport, maxSteps = 150))

        val detail = (result as ApplyResult.Failed).detail
        // Normal 20-read threshold resumed (not immediate "ignored shove" trip 2)
        assertThat(detail).contains("20 consecutive read-only steps")
        assertThat(detail).doesNotContain("ignored")
    }

    /**
     * Trip 1 is non-terminal: 20 read-only calls inject WANDER_CORRECTION and
     * the loop continues. Only trip 2 (ignoring the shove) ends the unit.
     * Regression guard for SHOULD #3 in code review.
     */
    @Test
    fun `wander trip 1 corrects and continues, does not fail`() {
        val transport = ScriptedTransport(buildList {
            repeat(20) { add(toolCallResponse("browser_snapshot", "{}")) }
            // model heeds the shove: an action clears the pending correction
            add(toolCallResponse("browser_click", """{"ref":"apply-btn"}"""))
            // then a skip_task to terminate cleanly
            add(toolCallResponse("skip_task", """{"reason":"portal-block","detail":"done"}"""))
        })

        val result = run(agent(transport, maxSteps = 50))

        // Trip 1 did NOT fail: the action after the correction was processed.
        assertThat(result).isInstanceOf(ApplyResult.Blocked::class.java)
        val allBodies = transport.bodies.joinToString("\n")
        assertThat(allBodies).contains("read-only calls without acting")
    }

    /**
     * SHOULD #4: a failed read-only call while wanderCorrectionPending is true
     * must still trip 2 immediately — a failed read is still reading, not acting.
     */
    @Test
    fun `failed read-only call after correction still trips wander breaker`() {
        val transport = ScriptedTransport(buildList {
            repeat(20) { add(toolCallResponse("browser_snapshot", "{}")) }
            add(toolCallResponse("browser_snapshot", "{}")) // this call will fail in the mock
        })

        var callCount = 0
        val throwingBrowser = mockk<McpStdioClient>(relaxed = true) {
            every { callToolText(any(), any(), any()) } answers {
                callCount++
                if (callCount > 20) throw Exception("simulated snapshot failure")
                "page snapshot"
            }
        }

        val agentWithThrowingBrowser = BrowserAgent(
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
            jobStateRepository = jobStateRepo,
            cvUploader = cvUploader,
            profileFiller = ProfileFiller(WorkerProperties()),
            mapper = mapper,
            maxSteps = 50,
        )

        val result = agentWithThrowingBrowser.run(
            jobId = "nofluffjobs:n-1",
            listingId = "65f0000000000000000000a1",
            applicantProfileText = "15 years Java",
            cvPathPl = "/app/cv/pl.pdf",
            mcpFactory = { throwingBrowser },
        )

        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("wander breaker: ignored corrective shove")
    }

    /**
     * SHOULD #5: stale-ref failure after trip 1 clears wanderCorrectionPending
     * (it's an action attempt, not a read), so a subsequent read-only call
     * starts a fresh streak — trip 2 needs another 20 reads.
     * Documents intended semantics: stale-ref shove != wander shove.
     */
    @Test
    fun `stale-ref failure after trip 1 clears pending correction`() {
        val transport = ScriptedTransport(buildList {
            repeat(20) { add(toolCallResponse("browser_snapshot", "{}")) }
            // model tries to fill but gets a stale-ref failure (an action attempt)
            add(toolCallResponse("browser_fill_form", """{"fields":{}}"""))
            // 20 more reads -> trip 2 at normal threshold (not immediate)
            repeat(20) { add(toolCallResponse("browser_snapshot", "{}")) }
        })

        var callCount2 = 0
        val failingBrowser = mockk<McpStdioClient>(relaxed = true) {
            every { callToolText(any(), any(), any()) } answers {
                callCount2++
                if (callCount2 == 21) {
                    // 21st call = the fill_form after 20 snapshots
                    throw Exception("Ref e29 not found in the current page snapshot")
                }
                "page snapshot"
            }
        }

        val agentWithFailingBrowser = BrowserAgent(
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
            jobStateRepository = jobStateRepo,
            cvUploader = cvUploader,
            profileFiller = ProfileFiller(WorkerProperties()),
            mapper = mapper,
            maxSteps = 150,
        )

        val result = agentWithFailingBrowser.run(
            jobId = "nofluffjobs:n-1",
            listingId = "65f0000000000000000000a1",
            applicantProfileText = "15 years Java",
            cvPathPl = "/app/cv/pl.pdf",
            mcpFactory = { failingBrowser },
        )

        val detail = (result as ApplyResult.Failed).detail
        assertThat(detail).contains("20 consecutive read-only steps")
        assertThat(detail).doesNotContain("ignored")
    }

    /**
     * NICE #2: boundary test — exactly 19 reads stays under the threshold.
     * Guards against off-by-one regressions in readStreak >= WANDER_THRESHOLD.
     */
    @Test
    fun `19 reads does not trip wander breaker`() {
        val transport = ScriptedTransport(buildList {
            repeat(19) { add(toolCallResponse("browser_snapshot", "{}")) }
            // then skip_task to terminate
            add(toolCallResponse("skip_task", """{"reason":"portal-block","detail":"done"}"""))
        })

        val result = run(agent(transport, maxSteps = 50))

        assertThat(result).isInstanceOf(ApplyResult.Blocked::class.java)
    }

    /**
     * NICE #3: browser_wait_for is a read-only tool and counts toward wander.
     */
    @Test
    fun `browser_wait_for counts as read-only for wander streak`() {
        val transport = ScriptedTransport(buildList {
            repeat(20) { add(toolCallResponse("browser_wait_for", """{"ms":100}""")) }
            add(toolCallResponse("browser_snapshot", "{}")) // trip 2 immediately
        })

        val result = run(agent(transport, maxSteps = 50))

        assertThat((result as ApplyResult.Failed).detail)
            .startsWith("wander breaker: ignored corrective shove")
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
    fun `narration fails the unit only after the limit, as TRANSIENT`() {
        val prose = """{"choices":[{"message":{"content":"thinking..."},"finish_reason":"stop"}]}"""
        val transport = ScriptedTransport(listOf(prose))

        val result = run(agent(transport, maxSteps = 10))

        // Transient, not Failed: a narration fluke must NOT wall the listing.
        // Thrown as ApplyRetryException by WorkerLoop -> the offset stays
        // uncommitted and the job redelivers with attempt-history context.
        assertThat(result).isInstanceOf(ApplyResult.Transient::class.java)
        assertThat((result as ApplyResult.Transient).detail)
            .startsWith("model produced no tool call after 3 tries")

        // Narration exhaustion is a serious failure now: the retry prompt
        // learns about this death from the attempt-history section.
        verify { recorder.recordAttemptHistory(any(), match { it.finalStatus.contains("no tool call") }) }
    }

    /**
     * The german-pracuj death (2026-10-27): the node MCP process died with a
     * V8 abort (exit=134) mid-grind; every later call failed instantly. A dead
     * MCP process must NOT escape run() as a raw exception - that bypasses
     * attempt-history recording and the infrastructure-signature check in
     * WorkerLoop.wallFailedReenqueue. Instead it must return ApplyResult.Transient
     * so Kafka redelivers to a fresh MCP process (mcpFactory spawns a new client
     * per run) and the retry prompt learns from the attempt history.
     */
    @Test
    fun `a dead MCP process returns Transient instead of escaping`() {
        every { browser.callToolText(any(), any(), any()) } throws
            McpClientException("call failed: MCP server process died (exit=134)")
        val transport = ScriptedTransport(
            listOf(toolCallResponse("browser_evaluate", """{"function":"1+1"}""")),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Transient::class.java)
        assertThat((result as ApplyResult.Transient).detail)
            .startsWith("MCP server process died")
        // Attempt history must be recorded so the retry prompt learns from this death.
        verify { recorder.recordAttemptHistory(any(), match { it.finalStatus.contains("process died") }) }
    }

    /**
     * 2026-11-05 wordpress death: the model reached the ATS form, ground
     * snapshots, then called skip_task with EMPTY arguments; the empty give-up
     * was accepted and wall-skipped the listing ("browser-failed: skipped:").
     * An unstated reason is an argument-fidelity flake, not a decision: refuse
     * it with a corrective nudge and continue the run.
     */
    @Test
    fun `an empty skip_task is refused with a nudge and the run continues`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("skip_task", "{}"),
                toolCallResponse("skip_task", """{"reason":"portal-block","detail":"dead form"}"""),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Blocked::class.java)
        // The refusal nudge reached the wire (the model saw it before retrying).
        assertThat(transport.bodies[1]).contains("REFUSED: skip_task needs a reason")
        verify(exactly = 0) { recorder.recordFailedWall(any(), any(), any()) }
    }

    /** A model that insists on empty give-ups requeues as Transient; never a wall. */
    @Test
    fun `a repeated empty skip_task ends the unit as Transient`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("skip_task", "{}"),
                toolCallResponse("skip_task", """{"reason":"   "}"""),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Transient::class.java)
        assertThat((result as ApplyResult.Transient).detail).contains("without a valid reason")
    }

    /**
     * 2026-11-05 copilot-studio: the model emitted single-quoted JSON in the
     * skip_task arguments; the parsed reason arrived as the literal garbage
     * "duplicate','detail", missed the duplicate branch and WALL-skipped the
     * listing. The intent must be mined from the garbage reason.
     */
    @Test
    fun `a garbage skip_task reason carrying a valid reason is mined`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("skip_task", """{"reason":"duplicate','detail"}"""),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.DuplicateNow::class.java)
        verify(exactly = 0) { recorder.recordFailedWall(any(), any(), any()) }
    }

    /**
     * 2026-11-05 specjalista-it: a garbage reason with NO valid one inside
     * (",") slipped past the blank check and WALL-skipped the listing via
     * Failed("skipped: ,"). An unrecognized reason takes the same
     * refuse/requeue path as an empty one - a skip flake is never a wall.
     */
    @Test
    fun `a garbage skip reason with no valid one inside requeues instead of walling`() {
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("skip_task", """{"reason":",","detail":""}"""),
                toolCallResponse("skip_task", """{"reason":".,.","detail":""}"""),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Transient::class.java)
        assertThat((result as ApplyResult.Transient).detail).contains("without a valid reason")
        verify(exactly = 0) { recorder.recordFailedWall(any(), any(), any()) }
    }

    /**
     * The architectural root cause of the 2026-11-05 step-40+ degeneration:
     * every tool result (up to 4000 chars) accumulated in the conversation
     * with no pruning, so by step ~40 the request exceeded the free model's
     * context window and the model degraded into snapshot-grinding and empty
     * give-ups. The window must stay bounded while keeping the task message.
     */
    @Test
    fun `conversation window stays bounded and keeps the task message`() {
        every { browser.callToolText(eq("browser_fill_form"), any(), any()) } returns "filled ok"
        val script = buildList {
            repeat(6) {
                repeat(5) { add(toolCallResponse("browser_snapshot", "{}")) }
                // An action between read waves keeps the wander breaker asleep.
                add(toolCallResponse("browser_fill_form", """{"fields":[{"name":"email","type":"textbox","value":"a@b.c"}]}"""))
            }
            add(toolCallResponse(
                "record_submission",
                """{"confirmationUrl":"https://x/ok","confirmationText":"ok","ats":"nfj"}""",
            ))
        }
        val transport = ScriptedTransport(script)

        val result = run(agent(transport, maxSteps = 45))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        transport.bodies.forEach { body ->
            val messages = mapper.readTree(body).path("messages")
            assertThat(messages.isArray).isTrue()
            // The wire body = conversation window + the per-request system message.
            assertThat(messages.size()).isLessThanOrEqualTo(BrowserAgent.MAX_CONTEXT_MESSAGES + 1)
            // The task message survives every prune (system is last; task first).
            assertThat(messages.first().path("content").asText()).contains("Apply to this job now")
        }
        // The window is actually exercised: the last request carries a full
        // window (system + MAX conversation messages), proving pruning ran.
        val last = mapper.readTree(transport.bodies.last()).path("messages")
        assertThat(last.size()).isEqualTo(BrowserAgent.MAX_CONTEXT_MESSAGES + 1)
        assertThat(last.last().path("role").asText()).isEqualTo("system")
    }

    /**
     * 2026-11-05 theprotocol quick-apply: the drawer form's fields sit at
     * chars 5.5k-14k of a 17k-char aria snapshot; the old 8k snapshot cap (and
     * the 4k append cap) made the form INVISIBLE to the model, which re-clicked
     * apply and gave up. A snapshot must reach the model past 8k chars.
     */
    @Test
    fun `a long snapshot reaches the model past the old 8k cut`() {
        val big = buildString {
            repeat(700) { append("listing padding line\n") } // ~14k chars of listing
            append("APPLY_FORM_EMAIL_FIELD \"E-mail*\" textbox\n")
            repeat(100) { append("form padding line\n") }
        }
        every { browser.callToolText(eq("browser_snapshot"), any(), any()) } returns big
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/ok","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        assertThat(transport.bodies[1]).contains("APPLY_FORM_EMAIL_FIELD")
    }

    /** Non-snapshot results stay lean; the head survives, the tail is cut. */
    @Test
    fun `non-snapshot tool results are capped at the lean limit`() {
        val long = buildString {
            append("HEAD_MARKER_clicked ")
            repeat(400) { append("filler ") }
            append("TAIL_MARKER_AT_3K")
        }
        every { browser.callToolText(eq("browser_click"), any(), any()) } returns long
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_click", """{"target":"f12"}"""),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/ok","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        assertThat(transport.bodies[1]).contains("HEAD_MARKER_clicked")
        assertThat(transport.bodies[1]).doesNotContain("TAIL_MARKER_AT_3K")
    }

    /**
     * Re-reading an UNCHANGED page returns the same snapshot; appending it
     * again doubled the context cost of every read-grind step. An identical
     * consecutive snapshot must REPLACE the previous one in the window.
     */
    @Test
    fun `an identical consecutive snapshot replaces instead of growing`() {
        val snapA = "SNAP-A-${"x".repeat(3000)}"
        val snapB = "SNAP-B-${"y".repeat(3000)}"
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/ok","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )
        every { browser.callToolText(eq("browser_snapshot"), any(), any()) } returnsMany
            listOf(snapA, snapA, snapB)

        run(agent(transport, maxSteps = 10))

        val sizes = transport.bodies.map { mapper.readTree(it).path("messages").size() }
        // body0: task+system = 2; after snapA: 3; identical snapA: still 3
        // (replaced); snapB appends: 4.
        assertThat(sizes).containsSubsequence(2, 3, 3, 4)
        assertThat(mapper.readTree(transport.bodies[2]).path("messages").last().path("content").asText())
            .doesNotContain("SNAP-B")
    }

    /**
     * 2026-11-06: a NEW page state supersedes the old one - when a second
     * MCP-shaped snapshot (### Page prefix) arrives, it REPLACES the previous
     * snapshot message instead of growing the window. Two full-page snapshots
     * must never coexist in the context.
     */
    @Test
    fun `a new snapshot replaces the previous page state`() {
        val snapA = "### Page\n- Page URL: https://x/a\n```yaml\n- generic [ref=e1]: A ${"x".repeat(2000)}```"
        val snapB = "### Page\n- Page URL: https://x/b\n```yaml\n- button \"Aplikuj\" [ref=e305]${"y".repeat(2000)}```"
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/ok","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )
        every { browser.callToolText(eq("browser_snapshot"), any(), any()) } returnsMany
            listOf(snapA, snapB)

        run(agent(transport, maxSteps = 10))

        val last = mapper.readTree(transport.bodies.last()).path("messages")
        // task + ONE snapshot (B replaced A) + system
        assertThat(last.size()).isEqualTo(3)
        assertThat(last[1].path("content").asText()).contains("ref=e305")
        assertThat(last[1].path("content").asText()).doesNotContain("Page URL: https://x/a")
    }

    /**
     * 2026-11-06 root cause of the theprotocol navigate-grinds: the MCP
     * snapshot runs 31k chars with the Aplikuj button (ref e305) at char
     * ~18k - past the 16k limit, the model never saw the apply control and
     * ground open_listing instead. The limit must carry the whole MCP
     * snapshot.
     */
    @Test
    fun `a full mcp snapshot reaches the model past the old 16k cut`() {
        val big = buildString {
            // Realistic shape: 31k total, button at ~17.5k - past the old 16k
            // cut, inside the 36k budget.
            append("### Page\n- Page URL: https://x/listing\n### Snapshot\n```yaml\n")
            repeat(400) { idx -> append("- generic [ref=e$idx]: listing padding line $idx\n") }
            append("- button \"Aplikuj\" [ref=e305] [cursor=pointer]\n")
            repeat(200) { idx -> append("- generic [ref=f$idx]: tail $idx\n") }
            append("```\n")
        }
        every { browser.callToolText(eq("browser_snapshot"), any(), any()) } returns big
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/ok","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )

        val result = run(agent(transport))

        assertThat(result).isInstanceOf(ApplyResult.Submitted::class.java)
        // The BUTTON line specifically (padding lines share the ref numbering);
        // quote-free substring because the wire body JSON-escapes the quotes.
        assertThat(transport.bodies[1]).contains("[ref=e305] [cursor=pointer]")
    }

    /**
     * Review S1 (2026-11-06): supersession must REMOVE the old snapshot and
     * append the new one at the TAIL. Replacing in place would let the fresh
     * snapshot inherit the old one's age and get pruned on the very next
     * result while stale results survived - losing the page state mid-form.
     */
    @Test
    fun `a superseding snapshot lands at the tail and survives the next prune`() {
        val snapA = "### Page\n- Page URL: https://x/a\n```yaml\n- generic [ref=e1]: A"
        val snapB = "### Page\n- Page URL: https://x/b\n```yaml\n- button \"Aplikuj\" [ref=e305] [cursor=pointer]"
        val transport = ScriptedTransport(
            listOf(
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_click", """{"target":"f1"}"""),
                toolCallResponse("browser_snapshot", "{}"),
                toolCallResponse("browser_click", """{"target":"f2"}"""),
                toolCallResponse(
                    "record_submission",
                    """{"confirmationUrl":"https://x/ok","confirmationText":"ok","ats":"nfj"}""",
                ),
            ),
        )
        every { browser.callToolText(eq("browser_snapshot"), any(), any()) } returnsMany
            listOf(snapA, snapB)
        every { browser.callToolText(eq("browser_click"), any(), any()) } returns "clicked"

        run(agent(transport, maxSteps = 10))

        // The LAST body must still carry snapB (the drawer with the apply
        // control) after two further results were appended post-supersession.
        assertThat(transport.bodies.last()).contains("ref=e305")
        assertThat(transport.bodies.last()).doesNotContain("Page URL: https://x/a")
    }
}
