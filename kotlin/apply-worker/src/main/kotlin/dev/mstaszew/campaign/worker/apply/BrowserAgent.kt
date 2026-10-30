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
import dev.mstaszew.campaign.common.domain.AttemptHistoryEntry
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
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

    /**
     * Model-flake failure that says nothing about the PORTAL: the listing is
     * not walled and the message redelivers (bounded by maxAttempts), so a
     * retry arrives with the history of this failure injected into its prompt.
     * Distinguishable from Failed, whose outcomes wall the listing: a prose
     * narration trip (2026-10-29 Angular alert: 3 tries of "browser_snapshot()"
     * text) is a formatting fluke, not a structural portal problem.
     */
    data class Transient(val detail: String) : ApplyResult()
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
    private val jobStateRepository: JobStateRepository,
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
        previousAttemptsHistory: List<AttemptHistoryEntry> = emptyList(),
    ): ApplyResult {
        val listing = listings.findById(listingId).orElse(null)
            ?: return ApplyResult.Failed("listing $listingId vanished")
        McpStdioClientClient(mcpFactory, jobId, mapper).use { browser ->
            val state = LoopState(jobId, listing, cvPathPl, jobStateRepository)
            val system = systemPrompt(listing, applicantProfileText, previousAttemptsHistory)
            var narrationTrips = 0

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
                ?: parseProseToolCall(response.content)
                    ?.also {
                        log.info(
                            "job {}: rescued prose tool call {} (model wrote the call as text)",
                            jobId, it.name,
                        )
                    }
                if (toolCall == null) {
                    // Free-tier models occasionally narrate instead of calling
                    // a tool (randlab 2026-10-27: one prose reply after an
                    // Apply click killed the unit). Nudge, and only fail after
                    // NARRATION_LIMIT consecutive tool-less replies. The
                    // failure is TRANSIENT (no wall): the formatting fluke
                    // says nothing about the portal, so the job redelivers
                    // with attempt-history context instead of burning the
                    // listing on one model flake (Angular 2026-10-29).
                    narrationTrips += 1
                    if (narrationTrips >= NARRATION_LIMIT) {
                        val result = out(
                            step,
                            ApplyResult.Transient(
                                "model produced no tool call after $narrationTrips tries: ${response.content.take(150)}",
                            ),
                        )
                        state.createAttemptHistoryEntry(result.detailText())?.let { recorder.recordAttemptHistory(jobId, it) }
                        return result
                    }
                    log.info("job {}: narration trip {}, nudging back to tools", jobId, narrationTrips)
                    state.messages += ChatMessage("user", NARRATION_NUDGE)
                    continue
                }
                val dispatchStart = System.nanoTime()
                when (val dispatched = dispatch(browser, toolCall, state)) {
                    is ApplyResult -> {
                        logStep(step, maxSteps, toolCall.name, "terminal", dispatchStart)
                        val result = out(step, dispatched)
                        state.createAttemptHistoryEntry(result.detailText())?.let { recorder.recordAttemptHistory(jobId, it) }
                        return result
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
                        if (!dispatched.failed && isReadOnlyCall(toolCall, state.wanderTrips >= 1)) {
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
                                val result = out(
                                    step,
                                    ApplyResult.Failed(
                                        "wander breaker: $WANDER_THRESHOLD consecutive read-only steps " +
                                            "without action (last read: ${dispatched.toolName})",
                                    ),
                                )
                                state.createAttemptHistoryEntry(result.detailText())?.let { recorder.recordAttemptHistory(jobId, it) }
                                return result
                            }
                        }
                        state.messages += ChatMessage("user", dispatched.text.take(4000))
                        if (state.failureStreak >= LOOP_BREAK_THRESHOLD) {
                            val result = out(
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
                            state.createAttemptHistoryEntry(result.detailText())?.let { recorder.recordAttemptHistory(jobId, it) }
                            return result
                        }
                        // Stale-ref auto-correction (pekao-conext 2026-10-27):
                        // "Ref e29 not found in the current page snapshot"
                        // repeated five times burned the unit - the model
                        // retried the SAME dead ref instead of re-snapshotting
                        // despite the prompt rule. A failed call carrying that
                        // error text gets a bounded corrective shove ordering a
                        // fresh snapshot; the failure streak above still ends
                        // the unit if the model ignores it.
                        if (dispatched.failed &&
                            STALE_REF_MARKERS.any { dispatched.text.contains(it) } &&
                            state.staleRefCorrections < STALE_REF_CORRECTION_LIMIT
                        ) {
                            state.staleRefCorrections += 1
                            log.warn(
                                "job {}: stale-ref failure {}, injecting snapshot correction",
                                jobId,
                                state.staleRefCorrections,
                            )
                            state.messages += ChatMessage("user", STALE_REF_CORRECTION)
                        }
                    }
                }
            }
            val capped = out(maxSteps, ApplyResult.Failed("step cap $maxSteps reached without outcome"))
            state.createAttemptHistoryEntry(capped.detailText())?.let { recorder.recordAttemptHistory(jobId, it) }
            return capped
        }
    }

    private class LoopState(
        val jobId: String,
        val listing: JobListingEntity,
        val cvPath: String,
        private val jobState: JobStateRepository,
    ) {
        /** Loop-breaker state: the tool name of the current failure streak and its length. */
        var failingTool: String? = null
        var failureStreak: Int = 0

        /** Wander-breaker state: consecutive READ-ONLY steps, whatever their tool mix. */
        var readStreak: Int = 0

        /** How many wander trips this unit has had; the second is terminal. */
        var wanderTrips: Int = 0

        /** Stale-ref corrective shoves injected so far (bounded, see the
         * dispatch-site stale-ref block). */
        var staleRefCorrections: Int = 0

        val messages = mutableListOf<ChatMessage>(
            ChatMessage(
                "user",
                "Apply to this job now: ${listing.roleTitle} at ${listing.company}. Listing: ${listing.url}. " +
                    "Use exactly one tool call per answer.",
            ),
        )
        var lastSnapshot: String = ""

        /** Create an AttemptHistoryEntry if this attempt failed with serious issues. */
        fun createAttemptHistoryEntry(finalStatus: String): AttemptHistoryEntry? {
            // Companion-level classifier: serious failures only (wander
            // breaker, loop breaker, portal-block, captcha, narration
            // exhaustion). Minor tool failures and step caps stay uncounted.
            return if (isSeriousFailure(finalStatus)) {
                val currentJobState = jobState.findById(jobId).orElse(null)
                val attempts = currentJobState?.attempts ?: 1 // Use current attempts or default to 1
                AttemptHistoryEntry(
                    attemptNumber = attempts,
                    wanderTrips = wanderTrips,
                    maxReadStreak = readStreak,
                    staleRefCorrections = staleRefCorrections,
                    finalStatus = finalStatus.take(200),
                )
            } else {
                null
            }
        }
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

    private fun systemPrompt(listing: JobListingEntity, profile: String, previousAttempts: List<AttemptHistoryEntry> = emptyList()): String {
        val historySection = if (previousAttempts.isNotEmpty()) {
            "\n\n> **Previous Attempts (to avoid repeating the same mistakes):**\n" +
            previousAttempts.map { entry ->
                "  - Attempt ${entry.attemptNumber}: wanderTrips=${entry.wanderTrips}, readStreak=${entry.maxReadStreak}, " +
                "staleRefCorrections=${entry.staleRefCorrections} → ${entry.finalStatus}"
            }.joinToString("\n") +
            "\n  **What to learn from this history:**\n" +
            "  - If readStreak is high (>10), avoid reading the same DOM repeatedly - take an action instead. " +
            "ONLY browser_click, browser_fill_form, upload_cv and record_submission count as actions: " +
            "browser_evaluate and browser_tabs are NOT actions.\n" +
            "  - If wanderTrips=1, the first correction was injected; take it seriously and try differently.\n" +
            "  - If a previous attempt died writing tool calls as prose text (\"no tool call\"), " +
            "emit every action as a real tool call in the tool_calls mechanism, never as text.\n" +
            "  - If this is the 2nd or 3rd attempt, you must succeed now - no more retries.\n"
        } else {
            ""
        }

        return """
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
            - After a click, if nothing seems to happen (URL unchanged, no
              confirmation), do NOT reason blindly: call browser_snapshot first -
              a MODAL may have opened on the same page (justjoin shows a consent
              modal whose confirm button also reads Aplikuj) - then
              browser_tabs(action="list") if the snapshot shows no modal, because
              the form may have opened in a NEW TAB.
            - If the page is stuck at a challenge or interstitial (typical text:
              "Just a moment", "Checking your browser", or an empty page),
              wait_for 5 seconds and check once more; if still blocked, call
              skip_task with reason portal-block - never keep re-reading a
              blocked page.
            - Login: the shared browser is signed into the correct Gmail. When
              you open a portal, check whether you are logged in (user avatar /
              profile menu); if not, and the portal offers Google SSO (button
              "Continue with Google" / "Zaloguj się przez Google"), log in that
              way IMMEDIATELY, before starting the apply. NEVER type an email or
              password and never create an account; if login is required and
              Google SSO is not offered, call skip_task with reason portal-block.
            - Before concluding the Apply button does not exist (skip_task
              portal-block "unable to locate Apply/Aplikuj"): SPA listing pages
              re-render and element refs go stale, so a lost button is USUALLY
              stale state, not a missing one (makeitright 2026-10-27: a live
              listing with a real Aplikuj button was walled after the model lost
              it between re-renders). First navigate to the LISTING URL fresh,
              take one browser_snapshot, and only skip if the Apply control and
              any Apply-opening modal are genuinely absent there.
            - Fill PL forms from the profile below (Polish identity fields); upload
              the PL CV ONLY via upload_cv - it owns the canonical file path, never
              call browser_file_upload and never invent a path; salary answer:
              15000 PLN net+VAT/month B2B.
            - If upload_cv answers "no related modal state" or "could not re-read
              the input node", there is NO CV input on the current page: take a
              fresh snapshot and continue the form without the CV (many forms
              upload later, or do not require it at all); retry upload_cv only
              after reaching a step that actually shows an upload/attach control.
            - browser_fill_form discipline: after any tab switch or page change,
              take a fresh browser_snapshot of the tab that holds the form and copy
              each field's exact target ref and exact visible label from THAT
              snapshot - never guess labels like "name" or "email". If a fill
              fails, re-snapshot and retry with the fresh refs; if one field still
              fails, fill the other fields in a separate call and retry the bad
              field alone. If the error says a ref is "not found in the current
              page snapshot" or says "Try capturing new snapshot", your VERY next
              call MUST be browser_snapshot - never retype a ref the error
              declared gone.
            - NEVER mention relocation or Israel anywhere.
            - Captcha: if a captcha/challenge widget (hCaptcha, reCAPTCHA,
              Turnstile, Cloudflare) is already VISIBLE when the form loads,
              attempt it exactly once BEFORE filling the form; if it does not
              solve, call skip_task with reason captcha IMMEDIATELY - do not
              fill the form first, it would be wasted. If the captcha appears
              only at submit, fill the form, attempt once, then skip_task.
            - If the portal says already applied, call skip_task with reason duplicate.
            - Before record_submission you MUST have visible confirmation evidence
              (thank-you text or success URL). No evidence means no record_submission.
            - Finish by calling record_submission (with the confirmation text/url you
              captured) or skip_task (reason: captcha | duplicate | portal-block).
            $historySection

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
            browser_click/browser_fill_form targets MUST be the element ref from
            the snapshot (like f12e4) - never CSS selectors or :has-text(), they
            resolve to multiple or zero elements.
        """.trimIndent()
    }

    /**
     * Human-readable failure text of an outcome; what
     * [LoopState.createAttemptHistoryEntry] classifies. Sealed subclasses
     * carry their reasons differently (Blocked has reason+detail, Failed
     * only detail), so each branch maps to the exact text the user/ops sees
     * in alerts and job_state.lastError.
     */
    private fun ApplyResult.detailText(): String =
        when (this) {
            is ApplyResult.Submitted -> "submitted"
            is ApplyResult.Blocked -> "$reason: $detail"
            is ApplyResult.DuplicateNow -> "duplicate: $detail"
            is ApplyResult.Failed -> detail
            is ApplyResult.Transient -> detail
        }

    /** One model-issued tool call: name + parsed argument object. */
    // internal so the narration-rescue unit tests can assert parsed shape.
    internal data class ToolCall(val name: String, val arguments: JsonNode)


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
            // A dead MCP server process is infrastructure, not a tool
            // failure: every subsequent call would fail against a corpse,
            // the streak would terminally fail the unit, and a perfectly
            // good listing would be walled (german-pracuj 2026-10-27,
            // exit=134 V8 abort). Escaping turns it into a Kafka
            // redelivery, and the next run spawns a fresh process.
            if (e.message?.contains("process died") == true) throw e
            log.warn("tool {} failed: {}", call.name, e.message)
            // 800: tonight's upload_cv deaths were undebuggable because this
            // cap cut the MCP refusal JSON (real reason lives past char 300)
            // before the loop breaker or the model ever saw it.
            var text = "tool ${call.name} failed: ${e.message?.take(800)}"
            // The ranlab class (2026-10-27): the model retried a CSS
            // selector five times against strict-mode violations because the
            // raw error never told it what to do instead.
            if (e.message?.contains("strict mode violation") == true ||
                e.message?.contains("does not match any elements") == true
            ) {
                text += " HINT: take a fresh browser_snapshot and use its element ref " +
                    "(like f12e4) as the target - CSS selectors and :has-text() do not work here."
            }
            ToolOutcome(call.name, text, failed = true)
        }

    companion object {
        val EFFECTIVE_MAX_STEPS: Int = AgentLoopBounds.effectiveMaxSteps
        val STEP_TIMEOUT: Duration = Duration.ofMillis(AgentLoopBounds.STEP_TIMEOUT_MS)
        val TOOL_TIMEOUT: Duration = Duration.ofMillis(AgentLoopBounds.TOOL_TIMEOUT_MS)

        /** Post-attempt blank navigate bound well below the tool timeout.
         *  Alias of TabCleanup.CALL_TIMEOUT, which now owns the bound. */
        val PAGE_CLEANUP_TIMEOUT: Duration = TabCleanup.CALL_TIMEOUT

        /**
         * Tool names the prose-rescue parser will accept. A restricted set
         * matters: the parser fires on messages that carry tool_calls
         * mechanism failures (free models emit the call as text), and a
         * wide-open set would turn ordinary prose mentioning a tool into a
         * spurious action. The model nudge teaches these exact names.
         */
        private val KNOWN_TOOLS = setOf(
            "browser_navigate",
            "browser_snapshot",
            "browser_click",
            "browser_tabs",
            "browser_fill_form",
            "upload_cv",
            "browser_evaluate",
            "browser_wait_for",
            "record_submission",
            "skip_task",
        )

        /**
         * Model-flake fallback tool-call parser: rescues replies where the
         * model WROTE the call as text ("browser_snapshot()", the 2026-10-29
         * Angular alert burned a listing on three such tries) instead of
         * emitting the OpenAI tool_calls JSON. Strict acceptance: the whole
         * trimmed content must be ONE call - it starts with a known tool
         * name, holds a balanced (...) argument block, and carries nothing
         * after the closing paren. Prose that merely MENTIONS a tool
         * ("I'll call browser_snapshot() next") stays a narration trip.
         */
        // internal: the test module sees it, but it is not part of any
        // public API surface (returns internal ToolCall).
        internal fun parseProseToolCall(content: String): ToolCall? {
            val trimmed = content.trim()
            val open = trimmed.indexOf('(')
            if (open <= 0) return null
            val name = trimmed.substring(0, open).trim()
            if (name !in KNOWN_TOOLS) return null
            val close = trimmed.lastIndexOf(')')
            // Balanced-one-block requirement: the '(' of the call is the FIRST
            // char-run, the ')' must be the content's LAST meaningful char.
            if (close < open) return null
            if (trimmed.substring(close + 1).isNotBlank()) return null
            val inner = trimmed.substring(open + 1, close).trim()
            val args = mapperForProseParse(inner)
            return if (args != null) ToolCall(name, args) else null
        }

        /**
         * Argument object from the inner text of a prose call. Shapes, in
         * order: empty -> {}; JSON object -> verbatim; "k=v" comma pairs ->
         * strings/numbers/booleans (quotes stripped, escapes kept verbatim;
         * negative numbers and integers hit NumberFormatException-coercing
         * paths guarded by toNumberOrNull). Array values inside "k=v" pairs
         * (fill_form's fields=[...]) must arrive as JSON, not prose.
         */
        private fun mapperForProseParse(inner: String): JsonNode? {
            val mapper = ObjectMapper()
            if (inner.isEmpty()) return mapper.createObjectNode()
            if (inner.startsWith("{") && inner.endsWith("}")) {
                return try {
                    val node = mapper.readTree(inner)
                    if (node.isObject) node else null
                } catch (e: Exception) {
                    null
                }
            }
            return proseKvpToJson(mapper, inner)
        }

        /**
         * Parse comma-separated key=value pairs; comma inside quotes is a
         * separator to CALL arguments, not list boundaries, e.g.
         * skip_task(reason="portal-block", detail="login wall, no SSO").
         */
        private fun proseKvpToJson(mapper: ObjectMapper, inner: String): JsonNode? {
            val obj = mapper.createObjectNode()
            var i = 0
            val n = inner.length
            while (i < n) {
                // key up to '='
                val eq = inner.indexOf('=', i)
                if (eq < 0) return null
                val key = inner.substring(i, eq).trim()
                if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
                // value: quoted string or bare token up to a top-level comma
                var j = eq + 1
                while (j < n && inner[j] == ' ') j++
                var valueText: String
                if (j < n && (inner[j] == '"' || inner[j] == '\'')) {
                    val quote = inner[j]
                    var k = j + 1
                    val sb = StringBuilder()
                    while (k < n && inner[k] != quote) {
                        if (inner[k] == '\\' && k + 1 < n) {
                            // keep the escape verbatim for the JSON parser at
                            // dispatch; only the closing quote must terminate
                            sb.append(inner[k]).append(inner[k + 1])
                            k += 2
                        } else {
                            sb.append(inner[k])
                            k += 1
                        }
                    }
                    if (k >= n) return null
                    valueText = sb.toString()
                    i = k + 1
                    obj.put(key, valueText)
                } else {
                    // bare token: number, boolean, or identifier
                    var k = j
                    while (k < n && inner[k] != ',') k++
                    valueText = inner.substring(j, k).trim().trimEnd(',')
                    if (valueText.isEmpty()) return null
                    valueText.toLongOrNull()?.let { obj.put(key, it) }
                        ?: valueText.toDoubleOrNull()?.let { obj.put(key, it) }
                        ?: when (valueText.lowercase()) {
                            "true" -> obj.put(key, true)
                            "false" -> obj.put(key, false)
                            else -> obj.put(key, valueText)
                        }
                    i = k
                }
                // skip separators/whitespace
                while (i < n && (inner[i] == ',' || inner[i] == ' ')) i++
            }
            return obj
        }

        /**
         * Attempt-history proper filter: only serious failures are recorded
         * for a NEXT attempt's prompt. Companion-level pure function so the
         * unit tests exercise the exact production filter without instantiating
         * the private LoopState.
         */
        fun isSeriousFailure(status: String): Boolean =
            status.contains("wander breaker") ||
                status.contains("loop breaker") ||
                status.contains("portal-block") ||
                status.contains("captcha") ||
                status.contains("no tool call")

        /** Consecutive same-tool failures that end the attempt as Failed.
         * Raised 3 -> 5 on 2026-10-26: the dominant failure is now a fast
         * (~30ms) fill_form rejection from a guessed label, and the inflancka
         * unit was lost to three of them while the winning natek submission
         * took two such rejections in stride (steps 25 and 44) and recovered.
         * The step cap still bounds the expensive timeout loops this breaker
         * was built for (2026-10-21/23), so the extra two chances cost at
         * most a few seconds.
         */
        const val LOOP_BREAK_THRESHOLD = 10

        /**
         * Consecutive READ-ONLY steps that end the attempt as Failed.
         * Reads are snapshot, tab LIST and wait_for; everything else
         * (including tab SELECT, which changes the active tab) is an action
         * and resets the streak. 8 leaves room for a legitimate re-read
         * after every action while cutting a pure read loop - single-tool
         * (109 snapshots in a row) or alternating (39 tabs-list + 30
         * snapshots, zero clicks) - at ~2 minutes instead of the full cap.
         */
        const val WANDER_THRESHOLD = 20

        /** The one-time corrective shove injected on the first wander trip. */
        const val WANDER_CORRECTION =
            "CORRECTION: you just made $WANDER_THRESHOLD consecutive read-only calls without acting. " +
                "Your next call MUST be an action: click an element, browser_fill_form, upload_cv, " +
                "or browser_tabs(action=\"select\", index=N - a bare index " +
                "defaults to LIST, always pass action=\"select\" when switching tabs). If there is " +
                "truly nothing to act on, call skip_task. WARNING: browser_evaluate and repeated " +
                "tab switching DO NOT count as actions - only click, fill_form, upload_cv, " +
                "record_submission do. Another read-only streak will end this task."

        /** Consecutive tool-less (prose) replies before the unit fails. */
        const val NARRATION_LIMIT = 3

        /**
         * Playwright-MCP refusal fragments that mean element refs went stale:
         * the page re-rendered (SPA) and the model must re-snapshot before
         * retrying, not retype the same ref (pekao-conext 2026-10-27).
         */
        private val STALE_REF_MARKERS = listOf(
            "not found in the current page snapshot",
            "Try capturing new snapshot",
        )

        /** Corrective shoves for stale refs before the failure streak ends the unit. */
        const val STALE_REF_CORRECTION_LIMIT = 10

        const val STALE_REF_CORRECTION =
            "CORRECTION: the ref you used no longer exists - the page re-rendered and ALL refs " +
                "may have changed. Your next call MUST be browser_snapshot; then re-issue the " +
                "failed action using ONLY refs and labels from THAT fresh snapshot. Never retype " +
                "a ref the error already declared gone."

        /** The nudge that pushes a narrating model back into tool use. */
        const val NARRATION_NUDGE =
            "You replied with prose instead of a tool call. Call EXACTLY ONE tool now: " +
                "browser_snapshot to see the current page or modal, browser_tabs(action=\"list\") " +
                "if a new tab may have opened, or skip_task to abandon. No prose."
        private val READ_ONLY_TOOLS = setOf("browser_snapshot", "browser_wait_for")

        /**
         * A tab call is a read only as a LIST; SELECT changes state.
         *
         * `tightenAfterWanderTrip`: once the model consumed its one corrective
         * shove, PSEUDO-ACTIONS stop resetting the read streak. The 2026-10-30
         * theprotocol PHP/Symfony grind rode to cap 150 on 104 snapshots +
         * 30 tab toggles + 12 evaluates, ZERO clicks or fills: tabs(SELECT)
         * and browser_evaluate counted as actions, so readStreak never
         * re-armed the second (terminal) wander trip. A legit external-ATS
         * tab switch is interleaved with real calls (fill_form, click) and
         * never accumulates 20 consecutive steps - only a wander grind does.
         */
        private fun isReadOnlyCall(call: ToolCall, tightenAfterWanderTrip: Boolean = false): Boolean =
            call.name in READ_ONLY_TOOLS ||
                (call.name == "browser_tabs" &&
                    call.arguments.path("action").asText("list").lowercase() != "select") ||
                (tightenAfterWanderTrip &&
                    (call.name == "browser_evaluate" || call.name == "browser_tabs"))

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
