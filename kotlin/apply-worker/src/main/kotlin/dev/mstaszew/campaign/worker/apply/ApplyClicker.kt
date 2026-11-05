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
        return clickThrough(browser, current, selector, waits, listTabs(browser))
    }

    /** The probe says enabled: click and report URL + tab state. */
    private fun clickThrough(
        browser: McpStdioClient,
        probe: com.fasterxml.jackson.databind.JsonNode,
        selector: String?,
        waits: Int,
        tabsBefore: String,
    ): String {
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
        val tabsAfter = listTabs(browser)
        return buildString {
            append("apply_click: clicked \"$text\"$waitedNote; URL after click: $url.\n")
            // 2026-11-05 inzynier: the model never switched to the ATS tab the
            // click opened - it ground listing-snapshot reads until the breaker
            // killed the run. The switch is worker-owned mechanics now: diff
            // the tab lists across the click and select the NEW tab.
            val newTab = newTabIndexOf(tabsBefore, tabsAfter)
            if (newTab != null) {
                val switched = try {
                    browser.callToolText(
                        "browser_tabs",
                        mapper.createObjectNode().apply {
                            put("action", "select")
                            put("index", newTab)
                        },
                        TOOL_TIMEOUT,
                    )
                    true
                } catch (e: Exception) {
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    log.warn("apply_click: auto-select of tab $newTab failed: {}", e.message)
                    false
                }
                if (switched) {
                    append("SWITCHED to the new ATS tab (index $newTab) - fill the form HERE: ")
                    append("profile_fill first, then browser_fill_form for the remaining fields, ")
                    append("upload_cv when an upload control appears, click submit, then ")
                    append("record_submission with the confirmation URL.\n")
                    append("Tabs now:\n").append(tabsAfter.take(800))
                    return@buildString
                }
            }
            // 2026-11-05 expert-poznan: some portals (theprotocol quick-apply)
            // open the form in a SAME-TAB drawer - no tab switch is possible,
            // and the model responded by re-clicking apply 5 times. Detect the
            // opened form and hand over the fill recipe.
            val inputs = formInputCount(browser)
            if (inputs >= FORM_OPEN_INPUTS) {
                append("QUICK-APPLY FORM IS OPEN on this page ($inputs inputs) - do NOT click apply again: ")
                append("profile_fill first, then browser_fill_form for the fields it reports missing, ")
                append("upload_cv when an upload control appears, click submit, then ")
                append("record_submission with the confirmation URL.")
                return@buildString
            }
            append("Tabs now:\n").append(tabsAfter.take(800)).append("\n")
            append("If a NEW tab holds the ATS form (traffit/lever/greenhouse/recruitee or an /apply URL), " +
                "browser_tabs(action=\"select\", index=N) to it and fill the form there. If this tab shows a " +
                "modal (e.g. \"Zaznacz czy zaaplikowales\") or /success, the click WORKED: take a browser_snapshot " +
                "and continue from what it shows.")
        }
    }

    /**
     * Visible form inputs on the current page (2026-11-05): >= 2 after an
     * apply click means the portal opened an inline/drawer application form.
     * The listing page itself carries 0-1 (theprotocol's only visible input
     * is a link-shortener box), so 2 is a safe discriminator.
     */
    private fun formInputCount(browser: McpStdioClient): Int =
        evaluate(browser, formCheckFunction(), FORM_CHECK_MARKER)?.path("inputs")?.asInt(0) ?: 0

    private fun formCheckFunction(): String =
        "(function(){ const els=[...document.querySelectorAll('input:not([type=hidden]):not([type=checkbox]), textarea, select')]" +
            ".filter(e=>e.offsetParent!==null); " +
            "return '${FORM_CHECK_MARKER}' + JSON.stringify({inputs:els.length, url:location.href}); })()"

    private fun listTabs(browser: McpStdioClient): String = try {
        browser.callToolText("browser_tabs", mapper.createObjectNode().apply { put("action", "list") }, TOOL_TIMEOUT)
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        log.warn("apply_click: tab list failed: {}", e.message)
        ""
    }

    /**
     * Index of the tab that appeared across the click, parsed from
     * playwright-mcp list lines ("- 1: [Title](url)"); null when nothing new
     * or the diff is ambiguous (multiple new tabs - the model chooses).
     */
    internal fun newTabIndexOf(before: String, after: String): Int? {
        val urlsBefore = TAB_LINE.findAll(before).map { it.groupValues[2] }.toSet()
        val newLines = TAB_LINE.findAll(after).filter { it.groupValues[2] !in urlsBefore }.toList()
        return if (newLines.size == 1) newLines.single().groupValues[1].toIntOrNull() else null
    }

    private val TAB_LINE = Regex("- (\\d+):.*\\(([^()]*)\\)\\s*$")

    private fun probe(browser: McpStdioClient, selector: String?): com.fasterxml.jackson.databind.JsonNode? =
        evaluate(browser, probeFunction(selector))

    /** Runs an evaluate and extracts the JSON after the given marker; null on failure. */
    private fun evaluate(browser: McpStdioClient, function: String, marker: String = MARKER): com.fasterxml.jackson.databind.JsonNode? = try {
        val text = browser.callToolText(
            "browser_evaluate",
            mapper.createObjectNode().apply { put("function", function) },
            TOOL_TIMEOUT,
        )
        McpEvaluateJson.extract(mapper, text, marker) ?: run {
            log.warn("apply_click: marker/JSON extraction failed; raw: {}", text.take(300))
            null
        }
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        log.warn("apply_click: evaluate failed: {}", e.message)
        null
    }

    /** Extracts the probe/click JSON; the shared MCP-shape logic lives in McpEvaluateJson. */
    internal fun extractProbeJson(text: String): com.fasterxml.jackson.databind.JsonNode? =
        McpEvaluateJson.extract(mapper, text, MARKER)

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

        /** Marker prefixing the post-click form-check report (see MARKER). */
        const val FORM_CHECK_MARKER = "APPLY_FORM_CHECK_9b12"

        /** Visible inputs that mean "an application form is open" (2026-11-05). */
        const val FORM_OPEN_INPUTS = 2

        /** One hydration wait; the NFJ button enables ~2s after domcontentloaded. */
        const val DISABLED_WAIT_MS: Long = 3000L

        /** Bounded: two waits cover the observed ~2s hydration; beyond that the page is broken. */
        private const val DISABLED_WAITS = 2

        private val TOOL_TIMEOUT: Duration = Duration.ofMillis(AgentLoopBounds.TOOL_TIMEOUT_MS)
    }
}
