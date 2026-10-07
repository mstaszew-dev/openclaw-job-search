package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.common.net.ChatMessage
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.net.LlmClientException
import dev.mstaszew.campaign.common.repo.ApplyTaskRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.worker.WorkerProperties
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
 * The LLM browser agent: one claimed task, one tab, one tool-call loop. The
 * model drives Playwright MCP tools (navigate/snapshot/click/fill/upload/
 * evaluate) plus two worker-side tools: `record_submission` (captures
 * confirmation evidence; validated by SubmissionVerifier via the Recorder) and
 * `skip_task`. Bounded by [WorkerProperties] lease and a hard step cap so a
 * looping model can never hold a task forever.
 */
@Component
class BrowserAgent(
    private val llm: LlmClient,
    private val recorder: Recorder,
    private val listings: JobListingRepository,
    private val tasks: ApplyTaskRepository,
    private val props: WorkerProperties,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    private val log = LoggerFactory.getLogger(BrowserAgent::class.java)

    fun run(
        taskId: Long,
        listingId: Long,
        applicantProfileText: String,
        cvPathPl: String,
        mcpFactory: () -> McpStdioClient,
    ): ApplyResult {
        val listing = listings.findById(listingId).orElse(null)
            ?: return ApplyResult.Failed("listing $listingId vanished")
        McpStdioClientClient(mcpFactory).use { browser ->
            val state = LoopState(taskId, listing)
            val system = systemPrompt(listing, applicantProfileText, cvPathPl)
            repeat(MAX_STEPS) {
                val response = try {
                    // per-step timeout well under the task lease; heartbeats below
                    llm.chat(state.messages + ChatMessage("system", system), maxTokens = 1500, timeout = STEP_TIMEOUT)
                } catch (e: LlmClientException) {
                    return ApplyResult.Failed("llm loop failed: ${e.message?.take(200)}")
                }
                // heartbeat: keep our claim alive while the agent works
                tasks.extendLease(taskId, props.workerId, props.leaseSeconds)
                val toolCall = parseToolCall(response.content)
                    ?: return ApplyResult.Failed("model produced no tool call: ${response.content.take(150)}")
                when (val dispatched = dispatch(browser, toolCall, state)) {
                    is ApplyResult -> return dispatched
                    else -> state.messages += ChatMessage("user", dispatched.toString().take(4000))
                }
            }
            return ApplyResult.Failed("step cap $MAX_STEPS reached without outcome")
        }
    }

    private class LoopState(val taskId: Long, val listing: dev.mstaszew.campaign.common.domain.JobListingEntity) {
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

    private fun systemPrompt(listing: dev.mstaszew.campaign.common.domain.JobListingEntity, profile: String, cvPath: String) = """
        You are the apply executor for a Polish B2B job-search campaign.
        Rules (hard):
        - One browser tab only; never open new tabs.
        - Fill PL forms from the profile below (Polish identity fields); use the
          PL CV file "$cvPath" for uploads; salary answer: 15000 PLN net+VAT/month B2B.
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
        browser_fill_form(fields), browser_file_upload(paths), browser_evaluate(function),
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

    private fun dispatch(browser: McpStdioClientClient, call: ToolCall, state: LoopState): Any {
        val toolTimeout = Duration.ofSeconds(120)
        return try {
            when (call.name) {
                "record_submission" -> {
                    val result = ApplyResult.Submitted(
                        confirmationUrl = call.arguments.path("confirmationUrl").asText(null),
                        confirmationText = call.arguments.path("confirmationText").asText(null),
                        ats = call.arguments.path("ats").asText(null),
                    )
                    recorder.recordSubmitted(state.listing, state.taskId, Recorder.Evidence(result.confirmationUrl, result.confirmationText, result.ats))
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
                "browser_snapshot" -> {
                    state.lastSnapshot = browser.client.callToolText("browser_snapshot", mapper.createObjectNode(), toolTimeout)
                    state.lastSnapshot.take(8000)
                }
                else -> browser.client.callToolText(call.name, call.arguments, toolTimeout).take(4000)
            }
        } catch (e: Exception) {
            log.warn("tool {} failed: {}", call.name, e.message)
            "tool ${call.name} failed: ${e.message?.take(300)}"
        }
    }

    companion object {
        private const val MAX_STEPS = 25
        private val STEP_TIMEOUT: Duration = Duration.ofSeconds(90)
    }
}
