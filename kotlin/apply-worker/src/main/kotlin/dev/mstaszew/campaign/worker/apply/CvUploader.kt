package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.worker.WorkerProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.time.Duration

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

        if (selector != null) {
            try {
                browser.callToolText(
                    "browser_click",
                    mapper.createObjectNode().apply { put("target", selector) },
                    TOOL_TIMEOUT,
                )
            } catch (e: Exception) {
                return "Error: could not open the file chooser ($selector): ${e.message?.take(200)}"
            }
        }

        // One re-arms attempt: the first click can land before the control's
        // handler is wired (late hydration) and playwright-mcp then refuses
        // the upload with "can only be used when there is related modal
        // state". A second click is cheap; the CDP fallback stays the last
        // resort because it needs a plain main-frame input[type=file].
        // Step-budget note: the worst case here is click + upload + re-click
        // + upload + evaluate = 5 x TOOL_TIMEOUT (600s) against the 210s a
        // step is modeled for in AgentLoopBounds; the consumer poll margin
        // (+30 min, KafkaWiring) absorbs the difference, which is acceptable
        // for a once-per-attempt tool rather than widening the model.
        val chooserErrors = mutableListOf<String>()
        val attempts = if (selector == null) 1 else 2
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
                    chooserErrors.add("re-click failed: ${e.message?.take(100)}")
                    break
                }
            }
            try {
                val args = mapper.createObjectNode().apply { putArray("paths").add(cvPath) }
                val accepted = browser.callToolText("browser_file_upload", args, TOOL_TIMEOUT)
                return "CV uploaded (${file.name}): $accepted"
            } catch (e: Exception) {
                chooserErrors.add(e.message?.take(100) ?: "unknown refusal")
            }
        }
        val chooserError = chooserErrors.joinToString("; ").take(200)

        // The chooser refused or never opened (drop zones). CDP fallback needs
        // the tab the MCP is actually driving; read it, but a failed read just
        // means the fallback must refuse rather than guess.
        log.info("browser_file_upload refused ({}); trying CDP fallback on {}", chooserError, cssInput)
        val activeUrl = try {
            browser.callToolText(
                "browser_evaluate",
                mapper.createObjectNode().apply { put("function", "() => location.href") },
                TOOL_TIMEOUT,
            ).lines().lastOrNull { it.isNotBlank() }?.trim()
        } catch (e: Exception) {
            null
        }

        val fallback = cdp.setFileInputFiles(props.cdpUrl, cvPath, cssInput, activeUrl)
        if (!fallback.startsWith("Error")) return "CV uploaded (${file.name}) $fallback"
        return "Error: CV upload failed via chooser (${chooserError?.take(150)}) and via CDP " +
            "(${fallback.take(150)}). Attach the CV manually: $cvPath"
    }

    companion object {
        const val DEFAULT_CSS_INPUT = "input[type=file]"
        val TOOL_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}
