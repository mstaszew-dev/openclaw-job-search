package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.messaging.AgentLoopBounds
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Deterministic worker-side Apply click (2026-11-04). The live NFJ runs that
 * day proved the free-tier model cannot reliably emit a browser_click: it
 * invented refs ("e1191919190"), sent empty targets, passed ":Aplikuj" as a
 * CSS selector, and could not compose a syntactically valid browser_evaluate
 * either. The click step therefore moves to worker code with ZERO
 * model-written arguments, mirroring upload_cv's worker-side pattern:
 *
 *   1. probe the page via browser_evaluate for the primary apply control
 *      (explicit selector first, else by exact label text);
 *   2. if it renders [disabled] (NFJ keeps #applyButton disabled until the
 *      SPA hydrates), wait and re-probe - a disabled button ignores every
 *      click, trusted or JS;
 *   3. click it via evaluate and report the resulting URL;
 *   4. list the tabs, because a working click opens the external ATS form in
 *      a NEW TAB (NFJ -> *.traffit.com, Lever, Greenhouse...) while the
 *      listing tab itself lands on /success.
 *
 * Never throws: every failure is an "Error: ..." string so the model can
 * fall back to browser_click with a real snapshot ref or skip_task. The
 * worker-written evaluate payloads are the ONLY JavaScript on this path; the
 * model never writes any.
 */
@Component
class ApplyClicker(
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {

    private val log = LoggerFactory.getLogger(ApplyClicker::class.java)
    private val mapper = ObjectMapper()

    fun click(browser: McpStdioClient, selector: String? = null): String {
        var current = probe(browser, selector)
            ?: return "Error: apply_click: could not probe the page for an apply control (evaluate failed); " +
                "take a browser_snapshot and use browser_click with a real ref instead"
        if (!current.path("found").asBoolean(false)) {
            return "Error: apply_click: no apply control found on this page " +
                "(looked for ${selector ?: "Aplikuj/Zaaplikuj/Apply by text"}); take a browser_snapshot and " +
                "use browser_click with a real ref, or skip_task with reason portal-block"
        }
        var waits = 0
        while (current.path("disabled").asBoolean(false)) {
            if (waits >= DISABLED_WAITS) {
                return "Error: apply_click: the apply control is STILL [disabled] after $waits wait(s) " +
                    "(hydration broken or consent gate); per the NFJ recipe call skip_task with reason portal-block"
            }
            sleeper(DISABLED_WAIT_MS)
            waits++
            current = probe(browser, selector)
                ?: return "Error: apply_click: re-probe after wait failed; take a browser_snapshot and continue"
            if (!current.path("found").asBoolean(false)) {
                return "Error: apply_click: the apply control vanished after a wait; take a browser_snapshot and continue"
            }
        }
        return clickThrough(browser, current, selector, waits)
    }

    /** The probe says enabled: click and report URL + tab state. */
    private fun clickThrough(browser: McpStdioClient, probe: com.fasterxml.jackson.databind.JsonNode, selector: String?, waits: Int): String {
        val clicked = evaluate(browser, clickFunction(selector))
            ?: return "Error: apply_click: click evaluate failed; take a browser_snapshot and use browser_click with a real ref"
        if (!clicked.path("clicked").asBoolean(false)) {
            // SPA re-render between probe and click: report the miss instead
            // of a fake success with an empty URL (review B1, 2026-11-04).
            return "Error: apply_click: the apply control vanished between probe and click (page re-rendered); " +
                "take a browser_snapshot and call apply_click again, or use browser_click with a real ref"
        }
        val url = clicked.path("url").asText("")
        val text = probe.path("text").asText("")
        val waitedNote = if (waits > 0) " after $waits hydration wait(s)" else ""
        val tabs = try {
            browser.callToolText("browser_tabs", mapper.createObjectNode().apply { put("action", "list") }, TOOL_TIMEOUT)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            log.warn("apply_click: tab list failed: {}", e.message)
            ""
        }
        return buildString {
            append("apply_click: clicked \"$text\"$waitedNote; URL after click: $url.\n")
            append("Tabs now:\n").append(tabs.take(800)).append("\n")
            append("If a NEW tab holds the ATS form (traffit/lever/greenhouse/recruitee or an /apply URL), " +
                "browser_tabs(action=\"select\", index=N) to it and fill the form there. If this tab shows a " +
                "modal (e.g. \"Zaznacz czy zaaplikowales\") or /success, the click WORKED: take a browser_snapshot " +
                "and continue from what it shows.")
        }
    }

    private fun probe(browser: McpStdioClient, selector: String?): com.fasterxml.jackson.databind.JsonNode? =
        evaluate(browser, probeFunction(selector))

    /** Runs an evaluate and extracts the JSON after the marker; null on failure. */
    private fun evaluate(browser: McpStdioClient, function: String): com.fasterxml.jackson.databind.JsonNode? = try {
        val text = browser.callToolText(
            "browser_evaluate",
            mapper.createObjectNode().apply { put("function", function) },
            TOOL_TIMEOUT,
        )
        extractProbeJson(text) ?: run {
            log.warn("apply_click: marker/JSON extraction failed; raw: {}", text.take(300))
            null
        }
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        log.warn("apply_click: evaluate failed: {}", e.message)
        null
    }

    /**
     * Extracts the probe/click JSON from the MCP evaluate text. The REAL
     * 2026-11-04 22:51 capture returns the string value JSON-ENCODED
     * ("...\"found\":true..."), so a naive {...} slice dies on the backslash
     * escapes ("Unexpected character ('\')"); the enclosing string must be
     * decoded first. The code echo after "### Ran Playwright code" repeats
     * the marker too, so every occurrence is tried. Internal for tests.
     */
    internal fun extractProbeJson(text: String): com.fasterxml.jackson.databind.JsonNode? {
        var idx = text.indexOf(MARKER)
        while (idx >= 0) {
            // Shape 1: a bare JSON object right after the marker (try the
            // nearest closing brace first, then the last one in the text).
            val start = text.indexOf('{', idx)
            if (start >= 0) {
                for (end in intArrayOf(text.indexOf('}', start), text.lastIndexOf('}'))) {
                    if (end > start) {
                        try {
                            return mapper.readTree(text.substring(start, end + 1))
                        } catch (_: Exception) {
                            // fall through to shape 2
                        }
                    }
                }
            }
            // Shape 2 (the real MCP shape): the value is a JSON-encoded
            // string - decode the enclosing "..." then parse the inner {...}.
            val openQuote = text.lastIndexOf('"', idx)
            if (openQuote >= 0) {
                var i = openQuote + 1
                while (i < text.length) {
                    when (text[i]) {
                        '\\' -> i += 2
                        '"' -> break
                        else -> i++
                    }
                }
                if (i < text.length) {
                    try {
                        val decoded = mapper.readValue(text.substring(openQuote, i + 1), String::class.java)
                        val ds = decoded.indexOf('{')
                        val de = decoded.lastIndexOf('}')
                        if (ds >= 0 && de > ds) {
                            return mapper.readTree(decoded.substring(ds, de + 1))
                        }
                    } catch (_: Exception) {
                        // fall through to the next marker occurrence
                    }
                }
            }
            idx = text.indexOf(MARKER, idx + 1)
        }
        return null
    }

    private fun probeFunction(selector: String?): String {
        val selJson = mapper.writeValueAsString(selector)
        // The marker rides in the EXPRESSION VALUE (not a source comment): the
        // value always reaches the MCP result text, while a comment only
        // survives if the MCP echoes the source. A model-supplied selector
        // (":Aplikuj" was observed live) may be invalid CSS: catch the throw
        // and fall through to the label search. Prefix match so "Aplikuj
        // teraz", "Apply now" and "Apply quickly" also hit (the prompt's own
        // label list).
        return "(function(){ const sel = $selJson; " +
            "let el = null; " +
            "try { el = sel && sel.length ? document.querySelector(sel) : null; } catch (e) { el = null; } " +
            "if (!el) { const cands = [...document.querySelectorAll('button, a, [role=button], input[type=submit]')]; " +
            "el = cands.find(b => /^(aplikuj|zaaplikuj|apply|quick apply)/i.test(((b.innerText||b.value||'')+'').trim())); } " +
            "if (!el) { return '${MARKER}' + JSON.stringify({found:false}); } " +
            "return '${MARKER}' + JSON.stringify({found:true, tag:el.tagName, disabled:!!el.disabled||el.getAttribute('aria-disabled')==='true', text:((el.innerText||el.value||'')+'').trim().slice(0,40)}); })()"
    }

    private fun clickFunction(selector: String?): String {
        val selJson = mapper.writeValueAsString(selector)
        return "(function(){ const sel = $selJson; " +
            "let el = null; " +
            "try { el = sel && sel.length ? document.querySelector(sel) : null; } catch (e) { el = null; } " +
            "if (!el) { const cands = [...document.querySelectorAll('button, a, [role=button], input[type=submit]')]; " +
            "el = cands.find(b => /^(aplikuj|zaaplikuj|apply|quick apply)/i.test(((b.innerText||b.value||'')+'').trim())); } " +
            "if (!el) { return '${MARKER}' + JSON.stringify({clicked:false}); } " +
            "el.click(); return '${MARKER}' + JSON.stringify({clicked:true, url:location.href}); })()"
    }

    companion object {
        /** Marker prefix anchoring the JSON in the MCP evaluate text. */
        const val MARKER = "APPLY_PROBE_MARKER_7f3a"

        /** One hydration wait; the NFJ button enables ~2s after domcontentloaded. */
        const val DISABLED_WAIT_MS: Long = 3000L

        /** Bounded: two waits cover the observed ~2s hydration; beyond that the page is broken. */
        private const val DISABLED_WAITS = 2

        private val TOOL_TIMEOUT: Duration = Duration.ofMillis(AgentLoopBounds.TOOL_TIMEOUT_MS)
    }
}
