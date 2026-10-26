package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.common.mcp.TabCleanup
import dev.mstaszew.campaign.common.messaging.AgentLoopBounds
import dev.mstaszew.campaign.common.net.ChatMessage
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.net.LlmClientException
import dev.mstaszew.campaign.common.net.ToolSpec
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.repo.JobListingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/** Outcome of one live apply attempt. */
sealed class ApplyResult {
    data class Submitted(
        val confirmationUrl: String?,
        val confirmationText: String?,
        val ats: String?,
    ) : ApplyResult()

    data class Blocked(val reason: String, val detail: String) : ApplyResult()

    data class DuplicateNow(val detail: String) : ApplyResult()

    data class Failed(val detail: String) : ApplyResult()
}

/**
 * The LLM browser agent: one Kafka message, one tab, one tool-call loop. The
 * model drives Playwright MCP tools (navigate/snapshot/click/fill/upload/
 * evaluate) plus two worker-side tools: `record_submission` (captures
 * confirmation evidence; validated by SubmissionVerifier via the Recorder) and
 * `skip_task`. Bounded by a hard step cap so a looping model can never run
 * forever, and a loop breaker aborts the attempt after LOOP_BREAK_THRESHOLD
 * consecutive failures of the same tool (2026-10-21: a dead listing retried
 * browser_navigate and a refused upload each burned the whole cap). There is
 * no lease to renew: the consumer's max.poll.interval.ms, not the
 * application, is what keeps a long apply from being evicted.
 *
 * The three timing constants are public because KafkaWiring derives the
 * consumer's poll interval from them. They lived here as private values, which
 * meant the poll budget could silently drift below the loop it has to cover.
 * PAGE_CLEANUP_TIMEOUT is deliberately NOT part of that budget: the
 * post-attempt tab cleanup (see McpStdioClientClient.close) runs after the
 * outcome is decided; its wedged-browser worst case (~35s: blank + tab list
 * + up to 5 closes, each 5s-bounded) rides the same consumer poll
 * margin that absorbs tool-level retries (KafkaWiring keeps +30 min of slack).
 */
@Component
class BrowserAgent(
    private val llm: LlmClient,
    private val recorder: Recorder,
    private val listings: JobListingRepository,
    private val cvUploader: CvUploader,
    private val mapper: ObjectMapper = ObjectMapper(),
    /** Injected for tests; production resolves WORKER_MAX_STEPS via AgentLoopBounds. */
    private val maxSteps: Int = AgentLoopBounds.effectiveMaxSteps,
) {

    private val log = LoggerFactory.getLogger(BrowserAgent::class.java)

    fun run(
        jobId: String,
        listingId: String,
        applicantProfileText: String,
        cvPathPl: String,
        mcpFactory: () -> McpStdioClient,
    ): ApplyResult {
        val listing = listings.findById(listingId).orElse(null)
            ?: return ApplyResult.Failed("listing $listingId vanished")
        McpStdioClientClient(mcpFactory, jobId, mapper).use { browser ->
            val state = LoopState(jobId, listing, cvPathPl)
            val system = systemPrompt(listing, applicantProfileText)

            // Step telemetry: one line per terminal outcome, so cap sizing is
            // driven by measured apply lengths instead of guesses.
            fun out(step: Int, result: ApplyResult): ApplyResult {
                log.info("job {}: {} after {} steps", jobId, result::class.simpleName, step)
                return result
            }

            // Per-step telemetry (round 5): cap-outs were unreconstructable
            // because only tool FAILURES were logged - the silent 9-minute
            // tail of a doomed unit left no trace. Tool name + outcome + ms,
            // no argument content (selectors can carry page PII).
            fun logStep(step: Int, maxSteps: Int, tool: String, outcome: String, startNanos: Long) {
                log.info(
                    "job {}: step {}/{} {} {} ({} ms)",
                    jobId, step, maxSteps, tool, outcome, (System.nanoTime() - startNanos) / 1_000_000,
                )
            }

            for (step in 1..maxSteps) {
                val response = try {
                    llm.chat(state.messages + ChatMessage("system", system), maxTokens = 1500, timeout = STEP_TIMEOUT, tools = TOOL_SCHEMAS)
                } catch (e: LlmClientException) {
                    return out(step, ApplyResult.Failed("llm loop failed: ${e.message?.take(200)}"))
                }
                val toolCall = response.toolCalls.firstOrNull { it.name.isNotBlank() }
                    ?.let { selected ->
                        // Free models sometimes fire several calls in one
                        // turn; the loop acts on exactly one step at a time.
                        val dropped = response.toolCalls
                            .filter { it !== selected && it.name.isNotBlank() }
                            .map { it.name }
                        if (dropped.isNotEmpty()) {
                            log.info("model offered {} tool calls; acting on {}, dropping {}", response.toolCalls.size, selected.name, dropped)
                        }
                        ToolCall(selected.name, selected.arguments)
                    }
                    ?: parseToolCall(response.content)
                    ?: return out(step, ApplyResult.Failed("model produced no tool call: ${response.content.take(150)}"))
                val dispatchStart = System.nanoTime()
                when (val dispatched = dispatch(browser, toolCall, state)) {
                    is ApplyResult -> {
                        logStep(step, maxSteps, toolCall.name, "terminal", dispatchStart)
                        return out(step, dispatched)
                    }
                    is ToolOutcome -> {
                        logStep(step, maxSteps, toolCall.name, if (dispatched.failed) "failed" else "ok", dispatchStart)
                        // Streak = CONSECUTIVE failures of the same tool (round
                        // 5 revision): a same-tool failure increments, a
                        // different-tool failure restarts at 1, and the stuck
                        // tool's OWN success is the only reset. Any other
                        // success - a snapshot read, but equally a no-op
                        // evaluate or wait_for(time) - proves nothing about
                        // the stuck action: the round-1 any-success-reset rule
                        // let snapshot-interleaved retry loops grind to the
                        // 80-step cap (4 cap-outs on 2026-10-23).
                        if (dispatched.failed && state.failingTool == dispatched.toolName) {
                            state.failureStreak += 1
                        } else if (dispatched.failed) {
                            state.failingTool = dispatched.toolName
                            state.failureStreak = 1
                        } else if (dispatched.toolName == state.failingTool) {
                            state.failingTool = null
                            state.failureStreak = 0
                        }
                        // Wander breaker: the failure streak cannot see a model
                        // stuck RE-READING (GET IT TOGETHER 2026-10-26: 109
                        // snapshots; sql-rybnik same day: 39 tab-lists + 30
                        // snapshots ALTERNATING, zero clicks) because those
                        // calls succeed. Count consecutive read-only steps in
                        // the streak whatever their mix; any action (click,
                        // fill, upload, navigate, evaluate, tab select) or any
                        // failure resets it.
                        if (!dispatched.failed && isReadOnlyCall(toolCall)) {
                            state.readStreak += 1
                        } else {
                            state.readStreak = 0
                        }
                        if (state.readStreak >= WANDER_THRESHOLD) {
                            if (state.wanderTrips == 0) {
                                // One corrective shove before the kill: a
                                // single degenerate model run should not
                                // permanently wall a listing (margo
                                // 2026-10-26). The second trip ends it.
                                state.wanderTrips = 1
                                state.readStreak = 0
                                log.warn("job {}: wander trip 1, injecting correction", jobId)
                                state.messages += ChatMessage("user", WANDER_CORRECTION)
                            } else {
                                return out(
                                    step,
                                    ApplyResult.Failed(
                                        "wander breaker: $WANDER_THRESHOLD consecutive read-only steps " +
                                            "without action (last read: ${dispatched.toolName})",
                                    ),
                                )
                            }
                        }
                        state.messages += ChatMessage("user", dispatched.text.take(4000))
                        if (state.failureStreak >= LOOP_BREAK_THRESHOLD) {
                            return out(
                                step,
                                ApplyResult.Failed(
                                    "loop breaker: ${dispatched.toolName} failed $LOOP_BREAK_THRESHOLD " +
                                        // 1200: a worst-case combined upload_cv error
                                        // (chooser 550 + CDP 400 + wrapper) runs ~1100
                                        // chars and the CDP section starts past 600;
                                        // shorter caps cut exactly before it.
                                        "consecutive times; last error: ${dispatched.text.take(1200)}",
                                ),
                            )
                        }
                    }
                }
            }
            return out(maxSteps, ApplyResult.Failed("step cap $maxSteps reached without outcome"))
        }
    }

    private class LoopState(val jobId: String, val listing: JobListingEntity, val cvPath: String) {
        /** Loop-breaker state: the tool name of the current failure streak and its length. */
        var failingTool: String? = null
        var failureStreak: Int = 0

        /** Wander-breaker state: consecutive READ-ONLY steps, whatever their tool mix. */
        var readStreak: Int = 0

        /** How many wander trips this unit has had; the second is terminal. */
        var wanderTrips: Int = 0

        val messages = mutableListOf<ChatMessage>(
            ChatMessage(
                "user",
                "Apply to this job now: ${listing.roleTitle} at ${listing.company}. Listing: ${listing.url}. " +
                    "Use exactly one tool call per answer.",
            ),
        )
        var lastSnapshot: String = ""
    }

    /**
     * A non-terminal tool result fed back to the model. `failed` marks the
     * tool's own failure (exception, or an upload_cv "Error: ..." verdict), as
     * opposed to a successful call whose TEXT reports a page-level problem -
     * only the former may count toward the loop breaker.
     */
    private data class ToolOutcome(val toolName: String, val text: String, val failed: Boolean)

    /** Wraps the per-task MCP client lifecycle. */
    private class McpStdioClientClient(
        private val factory: () -> McpStdioClient,
        private val jobId: String,
        private val mapper: ObjectMapper,
    ) : AutoCloseable {
        val client: McpStdioClient = factory().also { it.initialize("apply-worker", "0.1.0") }

        override fun close() {
            // Tab hygiene on EVERY exit path (decided outcomes via use{} and
            // recorder exceptions alike): the shared TabCleanup (common, also
            // used by the finder's collector; page blank now logs
            // "post-session page blank failed") blanks the CURRENT tab first,
            // then closes every non-survivor tab descending, capped and
            // 5s-bounded, best-effort. See TabCleanup for the full contract
            // (OOPIF orphans, survivor order, cookie-jar untouched).
            TabCleanup.run(client, mapper, "job $jobId")
            client.close()
        }
    }

    private fun systemPrompt(listing: JobListingEntity, profile: String) = """
        You are the apply executor for a Polish B2B job-search campaign.
        Rules (hard):
        - Stay in one tab. EXCEPTION: clicking Apply often opens the external
          application form in a NEW TAB (Lever, Greenhouse, Recruitee...);
          switch to it with browser_tabs(action="select", index=...) and finish
          the application there, capturing the confirmation URL from that tab.
          Never open tabs yourself except by clicking Apply.
        - If clicks time out or a cookie/consent overlay covers the page,
          dismiss it first (button "Accept all" / "Zaakceptuj wszystkie"),
          then retry the click.
        - Never re-read without acting: if two consecutive reads (snapshot,
          tab list, wait) show an unchanged page, your next call MUST be an
          action (click, fill, upload, evaluate, tab select) or skip_task.
        - If the portal requires login, use "Continue with Google" /
          "Zaloguj się przez Google" - the browser profile is already signed
          into the right Google account. NEVER type an email or password and
          never create an account; if Google login is not offered, call
          skip_task with reason portal-block.
        - Fill PL forms from the profile below (Polish identity fields); upload
          the PL CV ONLY via upload_cv - it owns the canonical file path, never
          call browser_file_upload and never invent a path; salary answer:
          15000 PLN net+VAT/month B2B.
        - browser_fill_form discipline: after any tab switch or page change,
          take a fresh browser_snapshot of the tab that holds the form and copy
          each field's exact target ref and exact visible label from THAT
          snapshot - never guess labels like "name" or "email". If a fill
          fails, re-snapshot and retry with the fresh refs; if one field still
          fails, fill the other fields in a separate call and retry the bad
          field alone.
        - NEVER mention relocation or Israel anywhere.
        - Captcha: attempt exactly once; if not solved, call skip_task with reason captcha.
        - If the portal says already applied, call skip_task with reason duplicate.
        - Before record_submission you MUST have visible confirmation evidence
          (thank-you text or success URL). No evidence means no record_submission.
        - Finish by calling record_submission (with the confirmation text/url you
          captured) or skip_task (reason: captcha | duplicate | portal-block).

        Applicant profile:
        $profile

        Tools: browser_navigate(url), browser_snapshot(), browser_click(element, target),
        browser_tabs(action, index) - list tabs or switch to the Apply tab,
        browser_fill_form(fields), upload_cv(selector, cssInput) - always pass the ref of
        the CV upload control as selector; upload_cv clicks it itself, so never click it
        first, browser_evaluate(function),
        browser_wait_for(time|text), record_submission(confirmationUrl, confirmationText, ats),
        skip_task(reason, detail).
        Call exactly one tool per turn.
    """.trimIndent()

    private data class ToolCall(val name: String, val arguments: JsonNode)

    private fun parseToolCall(content: String): ToolCall? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val node = mapper.readTree(content.substring(start, end + 1))
            val name = node.path("tool").asText(node.path("name").asText(""))
            if (name.isBlank()) null else ToolCall(name, node.path("arguments").takeIf { it.isObject } ?: node)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Worker-side tools are NOT wrapped in the browser-tool retry. That catch
     * exists so a flaky Playwright call is retried by the model; for the ledger
     * write, retrying is exactly wrong, because the model reacting to "the
     * record failed" is to submit the form a second time to a real employer.
     * A Mongo failure here must escape the loop so the offset stays
     * uncommitted and Kafka redelivers the job instead.
     */
    private fun dispatch(browser: McpStdioClientClient, call: ToolCall, state: LoopState): Any =
        when (call.name) {
            "record_submission" -> {
                val result = ApplyResult.Submitted(
                    confirmationUrl = call.arguments.path("confirmationUrl").asText(null),
                    confirmationText = call.arguments.path("confirmationText").asText(null),
                    ats = call.arguments.path("ats").asText(null),
                )
                recorder.recordSubmitted(
                    state.listing,
                    state.jobId,
                    Recorder.Evidence(result.confirmationUrl, result.confirmationText, result.ats),
                )
                result
            }
            "skip_task" -> {
                val reason = call.arguments.path("reason").asText("unknown")
                val detail = call.arguments.path("detail").asText("")
                when (reason) {
                    "duplicate" -> ApplyResult.DuplicateNow(detail)
                    "captcha", "portal-block" -> ApplyResult.Blocked(reason, detail)
                    else -> ApplyResult.Failed("skipped: $reason $detail")
                }
            }
            "upload_cv" ->
                // Worker-side like the ledger tools: the model picks the upload
                // control (selector), never the path, and a refused chooser is
                // escalated to CDP inside the tool instead of retried blindly.
                // Second never-throw layer (mirrors tools.py): upload_cv must
                // degrade one application, never escape the LLM loop into
                // Kafka redelivery.
                try {
                    cvUploader.upload(
                        browser.client,
                        state.cvPath,
                        selector = call.arguments.path("selector").asText(null)?.takeIf { it.isNotBlank() },
                        cssInput = call.arguments.path("cssInput").asText(CvUploader.DEFAULT_CSS_INPUT),
                    )
                } catch (e: Exception) {
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    "Error: upload_cv failed: ${e.message?.take(200)}"
                }.let { text ->
                    // "Error: ..." is upload_cv's fixed failure verdict
                    // (refused chooser, missing CV file); it is the loop fuel
                    // of the 2026-10-21 darize cap-out.
                    ToolOutcome("upload_cv", text, failed = text.startsWith("Error"))
                }
            else -> browserTool(browser, call, state)
        }

    private fun browserTool(browser: McpStdioClientClient, call: ToolCall, state: LoopState): ToolOutcome =
        try {
            val text = if (call.name == "browser_snapshot") {
                browser.client.callToolText("browser_snapshot", mapper.createObjectNode(), TOOL_TIMEOUT)
                    .also { state.lastSnapshot = it }
                    .take(8000)
            } else {
                // Free-tier backends routinely emit schema-shaped-wrong
                // arguments (fields as a map, evaluate script aliases); the
                // coercer normalizes the known shapes before they burn a step
                // on a zod error (and three of them trip the loop breaker).
                val wire = ArgCoerce.normalize(call.name, call.arguments, state.lastSnapshot)
                browser.client.callToolText(call.name, wire, TOOL_TIMEOUT).take(4000)
            }
            ToolOutcome(call.name, text, failed = false)
        } catch (e: Exception) {
            log.warn("tool {} failed: {}", call.name, e.message)
            // 800: tonight's upload_cv deaths were undebuggable because this
            // cap cut the MCP refusal JSON (real reason lives past char 300)
            // before the loop breaker or the model ever saw it.
            ToolOutcome(call.name, "tool ${call.name} failed: ${e.message?.take(800)}", failed = true)
        }

    companion object {
        val EFFECTIVE_MAX_STEPS: Int = AgentLoopBounds.effectiveMaxSteps
        val STEP_TIMEOUT: Duration = Duration.ofMillis(AgentLoopBounds.STEP_TIMEOUT_MS)
        val TOOL_TIMEOUT: Duration = Duration.ofMillis(AgentLoopBounds.TOOL_TIMEOUT_MS)

        /** Post-attempt blank navigate bound well below the tool timeout.
         *  Alias of TabCleanup.CALL_TIMEOUT, which now owns the bound. */
        val PAGE_CLEANUP_TIMEOUT: Duration = TabCleanup.CALL_TIMEOUT

        /**
         * Consecutive same-tool failures that end the attempt as Failed.
         * Raised 3 -> 5 on 2026-10-26: the dominant failure is now a fast
         * (~30ms) fill_form rejection from a guessed label, and the inflancka
         * unit was lost to three of them while the winning natek submission
         * took two such rejections in stride (steps 25 and 44) and recovered.
         * The step cap still bounds the expensive timeout loops this breaker
         * was built for (2026-10-21/23), so the extra two chances cost at
         * most a few seconds.
         */
        const val LOOP_BREAK_THRESHOLD = 5

        /**
         * Consecutive READ-ONLY steps that end the attempt as Failed.
         * Reads are snapshot, tab LIST and wait_for; everything else
         * (including tab SELECT, which changes the active tab) is an action
         * and resets the streak. 8 leaves room for a legitimate re-read
         * after every action while cutting a pure read loop - single-tool
         * (109 snapshots in a row) or alternating (39 tabs-list + 30
         * snapshots, zero clicks) - at ~2 minutes instead of the full cap.
         */
        const val WANDER_THRESHOLD = 8

        /** The one-time corrective shove injected on the first wander trip. */
        const val WANDER_CORRECTION =
            "CORRECTION: you just made $WANDER_THRESHOLD consecutive read-only calls without acting. " +
                "Your next call MUST be an action: click an element, browser_fill_form, upload_cv, " +
                "browser_evaluate, or browser_tabs(action=\"select\"). If there is truly nothing to " +
                "act on, call skip_task. Another read-only streak will end this task."

        private val READ_ONLY_TOOLS = setOf("browser_snapshot", "browser_wait_for")

        /** A tab call is a read only as a LIST; SELECT changes state. */
        private fun isReadOnlyCall(call: ToolCall): Boolean =
            call.name in READ_ONLY_TOOLS ||
                (call.name == "browser_tabs" &&
                    call.arguments.path("action").asText("list").lowercase() != "select")

        fun parseMaxSteps(raw: String?): Int = AgentLoopBounds.parseMaxSteps(raw)

        private val SCHEMA_MAPPER = ObjectMapper()

        /**
         * The tools as native OpenAI function schemas. The prompt lists them
         * too: the schema is what makes the gateway return parseable
         * tool_calls, the prompt is what teaches the model WHEN to call what.
         * The Python agent ran 1983 submissions on this contract.
         *
         * Property types mirror the consumers, not convenience: the six
         * forwarded browser tools are validated by @playwright/mcp's own
         * schemas (fill_form takes an ARRAY of field objects, wait_for takes
         * a NUMBER), and a stringified shape burns a step on a tool error.
         */
        private fun obj(vararg props: Pair<String, JsonNode>, required: List<String> = emptyList()): JsonNode =
            SCHEMA_MAPPER.createObjectNode().apply {
                put("type", "object")
                set<JsonNode>("properties", SCHEMA_MAPPER.createObjectNode().apply {
                    props.forEach { (name, node) -> set<JsonNode>(name, node) }
                })
                if (required.isNotEmpty()) {
                    set<JsonNode>("required", SCHEMA_MAPPER.createArrayNode().apply { required.forEach { add(it) } })
                }
            }

        private fun str(desc: String): JsonNode =
            SCHEMA_MAPPER.createObjectNode().put("type", "string").put("description", desc)

        private fun num(desc: String): JsonNode =
            SCHEMA_MAPPER.createObjectNode().put("type", "number").put("description", desc)

        /** fill_form.fields per tools.py: an array of {target,name,type,value} objects. */
        private fun fieldList(): JsonNode =
            SCHEMA_MAPPER.createObjectNode().apply {
                put("type", "array")
                put("description", "Form fields to fill")
                set<JsonNode>("items", SCHEMA_MAPPER.createObjectNode().apply {
                    put("type", "object")
                    set<JsonNode>("properties", SCHEMA_MAPPER.createObjectNode().apply {
                        listOf("target", "name", "type", "value").forEach { putObject(it).put("type", "string") }
                    })
                })
            }

        val TOOL_SCHEMAS: List<ToolSpec> = listOf(
            ToolSpec(
                "browser_navigate",
                "Open a URL in the single tab.",
                obj("url" to str("absolute URL to open"), required = listOf("url")),
            ),
            ToolSpec("browser_snapshot", "Read the page as an accessibility tree.", obj()),
            ToolSpec(
                "browser_click",
                "Click an element from the last snapshot.",
                obj(
                    "element" to str("element description from the snapshot"),
                    "target" to str("element ref/id from the snapshot"),
                    required = listOf("target"),
                ),
            ),
            ToolSpec(
                "browser_tabs",
                "List tabs or switch the active tab. Use when Apply opens the application form in a new tab.",
                obj(
                    "action" to str("list | select"),
                    "index" to num("tab index to select (from list)"),
                    required = listOf("action"),
                ),
            ),
            ToolSpec(
                "browser_fill_form",
                "Fill several fields at once. fields MUST be an ARRAY of {name, type, value} objects, not a map.",
                obj("fields" to fieldList(), required = listOf("fields")),
            ),
            ToolSpec(
                "upload_cv",
                "Upload the PL CV. Worker-side: it owns the canonical file path.",
                obj(
                    "selector" to str("optional clickable selector that opens the file chooser"),
                    "cssInput" to str("optional CSS selector of the <input type=file>"),
                ),
            ),
            ToolSpec(
                "browser_evaluate",
                "Run JavaScript in the page and return the result. Send an EXPRESSION or arrow function like () => document.title; top-level const/let/for statements are invalid.",
                obj("function" to str("JavaScript expression or arrow function to evaluate, e.g. () => document.title")),
            ),
            ToolSpec(
                "browser_wait_for",
                "Wait for text to appear or for seconds to pass.",
                obj("text" to str("text to wait for"), "time" to num("seconds to wait")),
            ),
            ToolSpec(
                "record_submission",
                "Record the application AFTER visible confirmation evidence. Terminal.",
                obj(
                    "confirmationUrl" to str("URL that proves the submission"),
                    "confirmationText" to str("thank-you text visible on the page"),
                    "ats" to str("ATS name, e.g. recruitee, workday"),
                ),
            ),
            ToolSpec(
                "skip_task",
                "Abandon this listing with a reason. Terminal.",
                obj("reason" to str("captcha | duplicate | portal-block"), "detail" to str("what happened")),
            ),
        )
    }
}
