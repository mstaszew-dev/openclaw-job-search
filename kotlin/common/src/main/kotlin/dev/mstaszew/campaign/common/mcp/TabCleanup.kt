package dev.mstaszew.campaign.common.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Unit-end tab hygiene for every consumer of the shared Chrome (CDP),
 * shared by the worker's BrowserAgent and the finder's McpListingCollector:
 * driven pages keep OOPIF iframes (adzuna/hubspot widgets, ad trackers,
 * target=_blank tabs) alive after the MCP session ends, and those orphan
 * targets stall every later playwright-mcp connectOverCDP at init (the
 * 2026-10-22 wedge: async initializeServer timeout with ws connected; the
 * 2026-10-24 recurrence: widget-laden pages orphan OOPIF targets within
 * the hour).
 *
 * ORDER IS LOAD-BEARING: blank the CURRENT tab first - its iframes die with
 * the page, and the session keeps a live page as the cleanup survivor
 * (closing the active page first would strand the session on a dead
 * reference). Tabs only: portal login state lives in the profile's cookie
 * jar, untouched here; only the mode-6 stack wipe drops it. This is the
 * campaign-dedicated Chrome, so there are no human tabs to protect, only
 * unit leftovers. Then close every non-survivor tab, descending so a close
 * never renumbers a pending one, capped so a leaked browser cannot turn
 * cleanup into an unbounded tail. Each step is 5s-bounded and best-effort:
 * a wedged browser must not stall its service (worst case ~35s once per
 * unit end vs hours of wedge).
 */
object TabCleanup {
    private val log = LoggerFactory.getLogger(TabCleanup::class.java)

    /** Per-call bound, well below the tool timeouts. */
    val CALL_TIMEOUT: Duration = Duration.ofSeconds(5)

    /** Leftover-tab closes per unit end; more get logged for attention. */
    const val MAX_CLOSES = 5

    /** playwright-mcp 0.0.83 lists tabs as "- 0: (current) [](about:blank)". */
    private val TAB_LINE = Regex("^-\\s*(\\d+):(\\s*\\(current\\))?", RegexOption.MULTILINE)

    /** Blank the current tab, then close every non-survivor tab. Best-effort. */
    fun run(client: McpStdioClient, mapper: ObjectMapper, label: String) {
        blankTab(client, mapper, label)
        closeLeftoverTabs(client, mapper, label)
    }

    private fun blankTab(client: McpStdioClient, mapper: ObjectMapper, label: String) {
        try {
            client.callToolText(
                "browser_navigate",
                mapper.createObjectNode().apply { put("url", "about:blank") },
                CALL_TIMEOUT,
            )
        } catch (e: Exception) {
            log.warn("{}: post-session page blank failed: {}", label, e.message?.take(120))
        }
    }

    private fun closeLeftoverTabs(client: McpStdioClient, mapper: ObjectMapper, label: String) {
        val listed = try {
            client.callToolText(
                "browser_tabs",
                mapper.createObjectNode().apply { put("action", "list") },
                CALL_TIMEOUT,
            )
        } catch (e: Exception) {
            log.warn("{}: tab list for cleanup failed: {}", label, e.message?.take(120))
            return
        }
        val tabs = TAB_LINE.findAll(listed)
            .mapNotNull {
                // toIntOrNull: a garbage index line is dropped here rather
                // than allowed to throw out of a caller's finally block.
                val index = it.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                TabRef(index, it.groupValues[2].isNotEmpty())
            }
            .toList()
        if (tabs.size <= 1) return
        val survivor = (tabs.firstOrNull { it.current } ?: tabs.minByOrNull { it.index }!!).index
        val extras = tabs.map { it.index }.filter { it != survivor }.sortedDescending()
        if (extras.size > MAX_CLOSES) {
            log.warn(
                "{}: {} leftover tabs; closing only the {} highest-indexed",
                label, extras.size, MAX_CLOSES,
            )
        }
        for (index in extras.take(MAX_CLOSES)) {
            try {
                client.callToolText(
                    "browser_tabs",
                    mapper.createObjectNode().apply {
                        put("action", "close")
                        put("index", index)
                    },
                    CALL_TIMEOUT,
                )
            } catch (e: Exception) {
                log.warn("{}: closing tab {} failed: {}", label, index, e.message?.take(120))
            }
        }
    }

    private data class TabRef(val index: Int, val current: Boolean)
}
