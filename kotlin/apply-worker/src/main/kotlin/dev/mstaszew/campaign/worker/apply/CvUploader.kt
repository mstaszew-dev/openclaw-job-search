package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.worker.WorkerProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.time.Duration
import java.util.UUID

/**
 * Ports cv_upload.py's upload_cv as a worker-side browser tool. The 2026-10-07
 * python review found the model improvising uploads: guessing paths, staging
 * copies, and calling browser_file_upload with no chooser open (seven stray CV
 * copies accumulated). This tool owns the whole sequence and the model never
 * picks a path:
 *   1. verify the canonical PL CV exists and is non-empty;
 *   2. click the upload control, if the model passed its ref;
 *   3. upload through the file chooser;
 *   4. if the chooser refused (drag-and-drop-only zone), fall back to CDP
 *      DOM.setFileInputFiles on the tab the MCP is driving;
 *   5. report the filename so the caller can attach it as evidence.
 *
 * Never throws: every failure is an Error string, so the model can skip_task
 * instead of crashing the loop. Worker-side on purpose: the browser-tool retry
 * wrapper is for flaky Playwright calls, not for a deterministic sequence.
 * Unlike python's resolve_cv there is no PL -> base CV fallback: the campaign
 * is PL-only, so a missing PL CV refuses rather than uploading the wrong file.
 */
@Component
class CvUploader(
    private val cdp: CdpFileUploader,
    private val props: WorkerProperties,
) {

    private val log = LoggerFactory.getLogger(CvUploader::class.java)
    private val mapper = ObjectMapper()

    fun upload(
        browser: McpStdioClient,
        cvPath: String,
        selector: String? = null,
        cssInput: String = DEFAULT_CSS_INPUT,
    ): String {
        val file = File(cvPath)
        if (!file.isFile) return "Error: CV not found at $cvPath"
        if (file.length() == 0L) return "Error: CV at $cvPath is empty (0 bytes)"

        val chooserErrors = mutableListOf<String>()
        var chooserPreArmed = false
        var clickFailed = false
        if (selector != null) {
            try {
                browser.callToolText(
                    "browser_click",
                    mapper.createObjectNode().apply { put("target", selector) },
                    TOOL_TIMEOUT,
                )
            } catch (e: Exception) {
                // A modal-state refusal means the model armed the chooser with
                // its own click; anything else means no chooser opened. A
                // failed pre-click must never preempt the CDP fallback (hidden
                // inputs time the click out while setFileInputFiles handles
                // them fine), so both cases fall through to the upload.
                if ((e.message ?: "").contains("does not handle the modal state")) {
                    chooserPreArmed = true
                    chooserErrors.add("pre-click skipped: chooser already armed")
                } else {
                    clickFailed = true
                    chooserErrors.add("pre-click failed: ${e.message?.take(250)}")
                }
            }
        }

        // A click can land before the control's handler is wired (late
        // hydration) and playwright-mcp then refuses the upload with "can
        // only be used when there is related modal state" - one re-arms
        // attempt covers that. A pre-click that itself failed (or a chooser
        // the model already armed) gets no re-click; the CDP fallback stays
        // the last resort because it needs a plain main-frame input[type=file].
        // Step-budget note: the worst case here is click + upload + re-click
        // + upload + evaluate = 5 x TOOL_TIMEOUT (600s) against the 210s a
        // step is modeled for in AgentLoopBounds; the consumer poll margin
        // (+30 min, KafkaWiring) absorbs the difference, which is acceptable
        // for a once-per-attempt tool rather than widening the model.
        val attempts = if (selector == null || chooserPreArmed || clickFailed) 1 else 2
        for (attempt in 1..attempts) {
            if (attempt > 1) {
                val target = requireNotNull(selector) { "retry implies a selector" }
                try {
                    browser.callToolText(
                        "browser_click",
                        mapper.createObjectNode().apply { put("target", target) },
                        TOOL_TIMEOUT,
                    )
                } catch (e: Exception) {
                    // 250: keep the MCP refusal past the JSON wrapper so the
                    // real reason ("related modal state" etc.) stays visible.
                    chooserErrors.add("re-click failed: ${e.message?.take(250)}")
                    break
                }
            }
            try {
                val args = mapper.createObjectNode().apply { putArray("paths").add(cvPath) }
                val accepted = browser.callToolText("browser_file_upload", args, TOOL_TIMEOUT)
                return "CV uploaded (${file.name}): $accepted"
            } catch (e: Exception) {
                chooserErrors.add(e.message?.take(250) ?: "unknown refusal")
            }
        }
        val chooserError = chooserErrors.joinToString("; ").take(550)

        // The chooser refused or never opened (drop zones). CDP fallback needs
        // the tab the MCP is actually driving; read it, but a failed read just
        // means the fallback must refuse rather than guess.
        log.info("browser_file_upload refused ({}); trying CDP fallback on {}", chooserError, cssInput)
        // The marker gives the fallback exact tab identity: several tabs can
        // share one form url (a stale erecruiter tab), and only the tab the
        // MCP is driving holds this window property.
        val marker = "cv-" + UUID.randomUUID().toString().substring(0, 8)
        val activeUrl = try {
            browser.callToolText(
                "browser_evaluate",
                mapper.createObjectNode().apply {
                    put("function", "() => { window.__cvUploadMarker = '$marker'; return location.href }")
                },
                TOOL_TIMEOUT,
            )
                // playwright-mcp fences evaluate output, so the last non-blank
                // line is the closing ``` - take the last url-looking line and
                // strip the wrapping quotes/backticks instead.
                .lines().lastOrNull { it.contains("://") }?.trim()?.trim('`', '"', '\'')
        } catch (e: Exception) {
            null
        }

        val fallback = cdp.setFileInputFiles(props.cdpUrl, cvPath, cssInput, activeUrl, marker)
        // One INFO line for the outcome: a silent success looks identical to
        // a wedged fallback in the pod log, and the next diagnosis pays for
        // that ambiguity.
        log.info("CDP fallback on {}: {}", cssInput, fallback.take(300))
        if (!fallback.startsWith("Error")) return "CV uploaded (${file.name}) $fallback"
        // 550/400: the 2026-10-22 NFJ deaths were undebuggable because the
        // caps cut the text exactly before the CDP fallback detail. Slack
        // truncates at its own edge regardless; logs must keep the reason.
        return "Error: CV upload failed via chooser (${chooserError}) and via CDP " +
            "(${fallback.take(400)}). Attach the CV manually: $cvPath"
    }

    companion object {
        const val DEFAULT_CSS_INPUT = "input[type=file]"
        val TOOL_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}
