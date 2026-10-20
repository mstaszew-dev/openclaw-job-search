package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.mcp.McpStdioClient
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
 * forever. There is no lease to renew: the consumer's max.poll.interval.ms,
 * not the application, is what keeps a long apply from being evicted.
 *
 * The three timing constants are public because KafkaWiring derives the
 * consumer's poll interval from them. They lived here as private values, which
 * meant the poll budget could silently drift below the loop it has to cover.
 */
@Component
class BrowserAgent(
    private val llm: LlmClient,
    private val recorder: Recorder,
    private val listings: JobListingRepository,
    private val cvUploader: CvUploader,
    private val mapper: ObjectMapper = ObjectMapper(),
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
        McpStdioClientClient(mcpFactory).use { browser ->
            val state = LoopState(jobId, listing, cvPathPl)
            val system = systemPrompt(listing, applicantProfileText)
            repeat(MAX_STEPS) {
                val response = try {
                    llm.chat(state.messages + ChatMessage("system", system), maxTokens = 1500, timeout = STEP_TIMEOUT, tools = TOOL_SCHEMAS)
                } catch (e: LlmClientException) {
                    return ApplyResult.Failed("llm loop failed: ${e.message?.take(200)}")
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
                    ?: return ApplyResult.Failed("model produced no tool call: ${response.content.take(150)}")
                when (val dispatched = dispatch(browser, toolCall, state)) {
                    is ApplyResult -> return dispatched
                    else -> state.messages += ChatMessage("user", dispatched.toString().take(4000))
                }
            }
            return ApplyResult.Failed("step cap $MAX_STEPS reached without outcome")
        }
    }

    private class LoopState(val jobId: String, val listing: JobListingEntity, val cvPath: String) {
        val messages = mutableListOf<ChatMessage>(
            ChatMessage(
                "user",
                "Apply to this job now: ${listing.roleTitle} at ${listing.company}. Listing: ${listing.url}. " +
                    "Use exactly one tool call per answer.",
            ),
        )
        var lastSnapshot: String = ""
    }

    /** Wraps the per-task MCP client lifecycle. */
    private class McpStdioClientClient(private val factory: () -> McpStdioClient) : AutoCloseable {
        val client: McpStdioClient = factory().also { it.initialize("apply-worker", "0.1.0") }
        override fun close() = client.close()
    }

    private fun systemPrompt(listing: JobListingEntity, profile: String) = """
        You are the apply executor for a Polish B2B job-search campaign.
        Rules (hard):
        - One browser tab only; never open new tabs.
        - Fill PL forms from the profile below (Polish identity fields); upload
          the PL CV ONLY via upload_cv - it owns the canonical file path, never
          call browser_file_upload and never invent a path; salary answer:
          15000 PLN net+VAT/month B2B.
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
        browser_fill_form(fields), upload_cv(selector, cssInput), browser_evaluate(function),
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
                }
            else -> browserTool(browser, call, state)
        }

    private fun browserTool(browser: McpStdioClientClient, call: ToolCall, state: LoopState): String =
        try {
            if (call.name == "browser_snapshot") {
                browser.client.callToolText("browser_snapshot", mapper.createObjectNode(), TOOL_TIMEOUT)
                    .also { state.lastSnapshot = it }
                    .take(8000)
            } else {
                browser.client.callToolText(call.name, call.arguments, TOOL_TIMEOUT).take(4000)
            }
        } catch (e: Exception) {
            log.warn("tool {} failed: {}", call.name, e.message)
            "tool ${call.name} failed: ${e.message?.take(300)}"
        }

    companion object {
        const val MAX_STEPS = 25
        val STEP_TIMEOUT: Duration = Duration.ofSeconds(90)
        val TOOL_TIMEOUT: Duration = Duration.ofSeconds(120)

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
                "browser_fill_form",
                "Fill several fields at once.",
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
                "Run JavaScript in the page and return the result.",
                obj("function" to str("JavaScript function body to evaluate")),
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
