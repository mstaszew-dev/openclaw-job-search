package dev.mstaszew.campaign.finder.collect

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.dedupe.CompanyKeyNormalizer
import dev.mstaszew.campaign.common.mcp.McpClientException
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.common.mcp.TabCleanup
import dev.mstaszew.campaign.common.net.LlmClientException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration

/**
 * Harvests listings by driving the shared Chrome (CDP) through the Playwright
 * MCP server: navigate to the board's remote-search page for one stack, let
 * the SPA settle, then browser_evaluate a per-board DOM extraction that reads
 * the rendered job cards. One board+stack per call; failures of a single
 * combination are logged and skipped so one bad page never blocks the run.
 */
class McpListingCollector(
    private val clientFactory: () -> McpStdioClient,
    private val stacks: List<String> = listOf("java", "kotlin", "php", "node", "react"),
    private val toolTimeout: Duration = Duration.ofSeconds(90),
    private val mapper: ObjectMapper = ObjectMapper(),
) : ListingCollector {

    override fun collect(source: String): List<CollectedListing> {
        val out = mutableListOf<CollectedListing>()
        clientFactory().use { client ->
            client.initialize("finder-service", "0.1.0")
            try {
                for (stack in stacks) {
                    try {
                        out += collectStack(client, source, stack)
                    } catch (e: Exception) {
                        when (e) {
                            is McpClientException, is LlmClientException -> log.warn(
                                "collect {} stack {} failed: {}",
                                source, stack, e.message,
                            )
                            else -> throw e
                        }
                    }
                }
            } finally {
                // Tab hygiene on every exit (shared TabCleanup, also used by
                // the worker's BrowserAgent): the board page and its OOPIF
                // iframes (hubspot chat, ad trackers) - plus any target=_blank
                // tabs it opened - outlive this MCP session, and those orphan
                // targets stall every later playwright-mcp connectOverCDP at
                // init (the 2026-10-22 wedge: async initializeServer timeout
                // with ws connected). Blank the CURRENT tab first, then close
                // every non-survivor tab descending; portal login state lives
                // in the profile cookie jar, untouched. Best-effort, never
                // blocks the run.
                TabCleanup.run(client, mapper, "collect $source")
            }
        }
        return out
    }

    private fun collectStack(client: McpStdioClient, source: String, stack: String): List<CollectedListing> {
        val pageUrl = pageUrl(source, stack)
        client.callToolText(
            "browser_navigate",
            mapper.createObjectNode().apply { put("url", pageUrl) },
            toolTimeout,
        )
        // let the SPA settle its own canonical redirects before evaluating;
        // bounded well below the tool timeout so a wedged wait_for cannot eat
        // 90s per stack (five wedged stacks nearly filled a 15-min window)
        runCatching {
            client.callToolText(
                "browser_wait_for",
                mapper.createObjectNode().apply { put("time", 3) },
                SETTLE_TIMEOUT,
            )
        }.onFailure { e ->
            log.warn("collect {} stack {}: settle wait failed: {}", source, stack, e.message?.take(120))
        }
        val extracted = client.callToolText(
            "browser_evaluate",
            mapper.createObjectNode().apply {
                put("function", "() => ${extractionScript(source)}")
            },
            toolTimeout,
        )
        return parse(source, extracted)
    }

    internal fun parseForTest(text: String, source: String): List<CollectedListing> = parse(source, text)

    private fun parse(source: String, text: String): List<CollectedListing> {
        val arrayText = extractArrayText(text) ?: return emptyList()
        val start = arrayText.indexOf('[')
        val end = arrayText.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array: JsonNode = runCatching { mapper.readTree(arrayText.substring(start, end + 1)) }
            .getOrNull() ?: return emptyList()
        return array.mapNotNull { toListing(source, it) }
    }

    /**
     * MCP wraps the evaluate result as `### Result\n"<json>"\n### Ran
     * Playwright code ...` - and the echoed code contains brackets of its own,
     * so a blind bracket scan grabs the wrong region. Pull the Result segment,
     * unwrap the JSON string node, and return the array text.
     */
    private fun extractArrayText(text: String): String? {
        val resultIndex = text.indexOf("### Result")
        if (resultIndex < 0) return text
        val rest = text.substring(resultIndex + "### Result".length)
        val nextSection = rest.indexOf("###")
        val segment = (if (nextSection > 0) rest.substring(0, nextSection) else rest).trim()
        if (segment.isEmpty()) return null
        val node = runCatching { mapper.readTree(segment) }.getOrNull() ?: return segment
        return when {
            node.isTextual -> node.textValue()
            node.isArray -> segment
            else -> null
        }
    }

    private fun toListing(source: String, n: JsonNode): CollectedListing? {
        // Board card ids can carry volatile session state in the query
        // (theprotocol hrefs append ?remote=true&s=...&searchId=<uuid> per
        // render); keep the path slug only, or one listing mints a new job
        // id every window (andrychow, 2026-10-23..24: three ids, three
        // grinds, one requisition).
        val jobId = n.path("id").asText("").substringBefore('?').substringBefore('#')
        val url = n.path("url").asText("")
        val company = n.path("company").asText("")
        val title = n.path("title").asText("")
        // fail closed: a card without an explicit remote flag is skipped
        val remote = n.path("remote").asBoolean(false)
        if (jobId.isBlank() || url.isBlank() || title.isBlank() || !remote) return null
        return CollectedListing(
            source = source,
            sourceJobId = jobId,
            company = company,
            roleTitle = title,
            url = url,
            remotePolicy = "remote",
            salaryMin = n.path("salaryMin").decimalOrNull(),
            salaryMax = n.path("salaryMax").decimalOrNull(),
            stack = n.path("stack").mapNotNull { it.asText(null) },
            rawJson = n.toString(),
        )
    }

    private fun JsonNode.decimalOrNull(): BigDecimal? {
        val text = if (isNumber) asText() else asText("").filter { it.isDigit() }
        return text.takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(McpListingCollector::class.java)

        /** Post-navigation settle wait (a fixed 3s sleep) bound, well below
         *  the tool timeout; the tab-cleanup bound lives in TabCleanup. */
        private val SETTLE_TIMEOUT: Duration = Duration.ofSeconds(10)

        fun pageUrl(source: String, stack: String): String = when (source) {
            "nofluffjobs" -> "https://nofluffjobs.com/pl/$stack?criteria=cityId%3Dremote"
            "justjoin" -> "https://justjoin.it/all-remote/$stack"
            "theprotocol" -> "https://theprotocol.it/filtry/$stack?remote=true"
            else -> throw IllegalArgumentException("unknown board source: $source")
        }

        private val CARD_SCRIPT = """(function(){
            const selector = __SELECTOR__;
            const seen = new Map();
            document.querySelectorAll(selector).forEach(a => {
                const href = a.getAttribute('href') || '';
                const id = href.split('/').filter(Boolean).pop() || '';
                const lines = (a.innerText || '').split('\n').map(s => s.trim()).filter(Boolean);
                const uiNoise = /^(zapisz|zapis|zł|pln|zdaln|premia|bon)|^\d|\d[-–]\d|\/full\//i;
                const chipNoise = /^(__BADGES__)$/i;
                const titleLine = lines[0];
                const company = lines.slice(1).find(l =>
                    l.length > 1 && l !== titleLine && !uiNoise.test(l) && !chipNoise.test(l)) || '';
                if (!id || seen.has(id) || lines.length === 0) return;
                seen.set(id, {
                    remote: true,
                    id: id,
                    title: titleLine.slice(0, 140),
                    company: company.slice(0, 120),
                    url: location.origin + href
                });
            });
            return JSON.stringify([...seen.values()]);
        })()"""

        /**
         * DOM-based extraction: the boards no longer ship listing JSON in
         * __NEXT_DATA__; job cards are plain links (href carries the id slug,
         * card text carries title + company). Returns a JSON array string.
         * Best-effort: a board redesign degrades to an empty batch.
         */
        fun extractionScript(source: String): String = when (source) {
            "nofluffjobs" -> scriptFor("'a[href*=\"/pl/job/\"]'")
            "justjoin" -> scriptFor("'a[href*=\"/offers/\"]'")
            "theprotocol" -> scriptFor("'a[href*=\"/praca/\"]'")
            else -> throw IllegalArgumentException("unknown board source: $source")
        }

        /**
         * Whole-line board chip words for the card text filter, built from the
         * dedupe chip vocabulary (hyphens become spaces in rendered card text)
         * plus extractor-only extras: "remote" is a chip line here but stays a
         * real-employer key in dedupe (Remote.com), and "AI/ML" is the
         * slash-rendered variant of the "ai-ml" chip. Anchored `^...$` so real
         * company lines like "B2B Group" survive. The slash is escaped for the
         * JS regex literal this is interpolated into.
         */
        internal fun chipBadgeAlternation(): String =
            (CompanyKeyNormalizer.CHIP_KEYS.map { it.replace('-', ' ') } + "remote" + "ai/ml")
                .joinToString("|")
                .replace("/", "\\/")

        private fun scriptFor(selector: String): String = CARD_SCRIPT
            .replace("__SELECTOR__", selector)
            .replace("__BADGES__", chipBadgeAlternation())
    }
}
