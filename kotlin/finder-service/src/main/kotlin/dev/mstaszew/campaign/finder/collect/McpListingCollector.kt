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
    private val stacks: List<String> = DEFAULT_STACKS,
    private val toolTimeout: Duration = Duration.ofSeconds(90),
    private val mapper: ObjectMapper = ObjectMapper(),
) : ListingCollector {

    /** Visible for the default-list test. */
    internal fun stacksForTest(): List<String> = stacks

    override fun collect(source: String): List<CollectedListing> {
        val out = mutableListOf<CollectedListing>()
        clientFactory().use { client ->
            client.initialize("finder-service", "0.1.0")
            try {
                val attempted = mutableSetOf<String>()
                for (stack in stacks) {
                    for (page in pageVariants(source, pageUrl(source, stack))) {
                        // Stacks can share one target page (justjoin folds
                        // kotlin into java, node and react into javascript);
                        // a second visit would only burn a settle wait and
                        // inflate the dupWindow counter. Marked attempted
                        // (not scraped) before the try: a failed page does
                        // not get a free retry from its folded twin; the
                        // next window recovers it.
                        if (!attempted.add(page)) continue
                        try {
                            out += collectStack(client, source, stack, page)
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

    private fun collectStack(client: McpStdioClient, source: String, stack: String, page: String): List<CollectedListing> {
        client.callToolText(
            "browser_navigate",
            mapper.createObjectNode().apply { put("url", page) },
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
                put("function", "() => ${extractionScript(source, stack)}")
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

        /**
         * 2026-10-26 inflow expansion: the original five stack queries were
         * 100% swept (every window enqueued 0), and NFJ serves real category
         * pages for spring, typescript and javascript. justjoin and
         * theprotocol fold the new stacks to their nearest real chips (see
         * pageUrl) so their per-window page count stays at three plus the
         * historical kotlin/node pages.
         */
        val DEFAULT_STACKS: List<String> =
            listOf("java", "kotlin", "spring", "php", "node", "react", "javascript", "typescript")

        /** Post-navigation settle wait (a fixed 3s sleep) bound, well below
         *  the tool timeout; the tab-cleanup bound lives in TabCleanup. */
        private val SETTLE_TIMEOUT: Duration = Duration.ofSeconds(10)

        fun pageUrl(source: String, stack: String): String = when (source) {
            "nofluffjobs" -> "https://nofluffjobs.com/pl/$stack?criteria=cityId%3Dremote"
            // 2025 redesign: /all-remote/* became 404 and the remote filter
            // moved to /job-offers/remote/<category>. kotlin, spring, node,
            // react and typescript have no category of their own (kotlin and
            // spring post under java, node, react and typescript under
            // javascript), so they fold onto the nearest real one.
            "justjoin" -> "https://justjoin.it/job-offers/remote/${justjoinCategory(stack)}"
            "theprotocol" -> "https://theprotocol.it/filtry/${protocolChip(stack)}?remote=true"
            // Keyword SEARCH board (the largest PL board, 956 java offers vs
            // ~150 per category elsewhere): each keyword is a real search;
            // the remote filter rides the URL, so every card is remote and
            // the extractor can hardcode remote:true like the other boards.
            "pracuj" -> "https://www.pracuj.pl/praca/${pracujKeyword(stack)};kw/praca%20zdalna;wm,home-office"
            else -> throw IllegalArgumentException("unknown board source: $source")
        }

        private fun justjoinCategory(stack: String): String = when (stack) {
            "kotlin", "spring" -> "java"
            "node", "react", "typescript" -> "javascript"
            else -> stack
        }

        /**
         * Five distinct pracuj searches per window (java, kotlin, php,
         * react, javascript); the remaining stacks fold onto them so the
         * window budget stays sane (~20s per search page).
         */
        private fun pracujKeyword(stack: String): String = when (stack) {
            "spring" -> "java"
            "node", "typescript" -> "javascript"
            else -> stack
        }

        /**
         * justjoin paginates with ?page=N (verified live 2026-10-26/27:
         * pages 2 and 3 each serve a different set of 50 offers per
         * category, and the board's meta claims 738 remote offers vs the
         * ~150 page 1 yields). The other boards have no verified page param
         * yet: one page each.
         */
        internal fun pageVariants(source: String, page: String): List<String> =
            if (source == "justjoin") listOf(page, "$page?page=2", "$page?page=3") else listOf(page)

        /**
         * spring and typescript are NOT theprotocol chips: their /filtry
         * pages silently fall back to the generic offer pool (verified in a
         * real browser 2026-10-26), which would scrape the same general pool
         * as java/javascript every window. Fold to the nearest real chip.
         */
        private fun protocolChip(stack: String): String = when (stack) {
            "spring" -> "java"
            "typescript" -> "javascript"
            else -> stack
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
        fun extractionScript(source: String, stack: String = ""): String = when (source) {
            "nofluffjobs" -> scriptFor("'a[href*=\"/pl/job/\"]'")
            // The 2025 listing page has no anchor cards at all (cards are
            // MUI divs), but it server-renders a JSON-LD ItemList with every
            // offer URL and title, and the hydrated cards carry the company
            // as their first text line. The script joins URL-by-title and
            // emits the card shape parse() already understands; a card that
            // has not hydrated yields an empty company, which only widens
            // the company dedupe, never breaks id or url matching. The
            // scraped category is emitted as the stack so titles without a
            // stack word still pass the eligibility stack gate.
            "justjoin" -> JUSTJOIN_JSONLD_SCRIPT.replace("__STACK__", justjoinCategory(stack))
            "theprotocol" -> scriptFor("'a[href*=\"/praca/\"]'")
            // Keyword-search board: the offer anchor itself carries the card
            // text (badge / title / salary? / company / city / employment
            // chips incl. "Praca zdalna"). Anchors inside dialogs are promo
            // CTAs, not listings. The URL query (?s=&searchId=) is session
            // state and parse() already strips it from the id.
            "pracuj" -> PRACUJ_SCRIPT.replace("__STACK__", pracujKeyword(stack))
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

        /**
         * pracuj.pl card extraction, validated against the SHARED Chrome on
         * 2026-10-27 (finder-pod probe): the offer anchors themselves are
         * thin (wrapper and title-only links, under 3 text lines); the card
         * text (badge? / title / salary? / company / city / employment
         * chips incl. "Praca zdalna") lives in the enclosing container, so
         * the script walks UP from each anchor to the first container with
         * card-shaped text. Promo CTAs inside dialogs (a recruiter upsell
         * overlays the page) are excluded. The stack rides the keyword
         * search that produced the page.
         */
        private val PRACUJ_SCRIPT = """(function(){
            const badge = /^(Superoferta|Oferta wyróżniona|Nowa|Top|Promowana)/i;
            const salary = /zł|PLN/i;
            const noise = /^(Sprawdź profil firmy|Opublikowana|Aplikuj szybko)/i;
            const seen = new Set();
            const out = [];
            document.querySelectorAll('a[href*=",oferta,"]').forEach(a => {
                if (a.closest('dialog')) return;
                const href = a.getAttribute('href') || '';
                const id = (href.split('/').filter(Boolean).pop() || '').split('?')[0].split('#')[0];
                if (!id || seen.has(id)) return;
                let node = a, lines = [];
                for (let i = 0; i < 8 && node && node !== document.body; i++) {
                    node = node.parentElement;
                    if (!node) break;
                    const l = (node.innerText || '').split('\n').map(s => s.trim()).filter(Boolean);
                    if (l.length >= 4 && node.innerText.length < 700) { lines = l; break; }
                }
                if (lines.length < 4) return;
                let i = 0;
                while (i < lines.length && (badge.test(lines[i]) || noise.test(lines[i]))) i++;
                const title = lines[i] || '';
                i++;
                let company = '';
                while (i < lines.length) {
                    const l = lines[i];
                    if (!salary.test(l) && !noise.test(l) && !badge.test(l) && l.length > 1) { company = l; break; }
                    i++;
                }
                if (!title || !company) return;
                seen.add(id);
                out.push({
                    remote: true,
                    id: id,
                    title: title.slice(0, 140),
                    company: company.slice(0, 120),
                    stack: ["__STACK__"],
                    url: href.split('?')[0]
                });
            });
            return JSON.stringify(out);
        })()"""

        private fun scriptFor(selector: String): String = CARD_SCRIPT
            .replace("__SELECTOR__", selector)
            .replace("__BADGES__", chipBadgeAlternation())

        /**
         * justjoin's redesigned listing page, validated against the live DOM
         * on 2026-10-25 (39/39 offers extracted with companies on
         * /job-offers/remote/java). Cards are no longer anchors, so the
         * offer URLs come from the SSR JSON-LD ItemList and the company from
         * the card text (first line above the title heading). Offers are
         * keyed per URL, never per title: two live offers with the same
         * title must both survive the join.
         */
        private val JUSTJOIN_JSONLD_SCRIPT = """(function(){
            const offers = [];
            document.querySelectorAll('script[type="application/ld+json"]').forEach(sc => {
                let data; try { data = JSON.parse(sc.textContent || ''); } catch (e) { return; }
                const nodes = Array.isArray(data && data['@graph']) ? data['@graph'] : [data];
                nodes.forEach(n => {
                    const list = n && n.mainEntity && n.mainEntity.itemListElement;
                    if (!Array.isArray(list)) return;
                    list.forEach(li => {
                        const url = (li && li.url) || '';
                        const name = (li && li.name) || '';
                        if (url.indexOf('/job-offer/') < 0 || !name) return;
                        offers.push({ url: url, name: name });
                    });
                });
            });
            const companyOf = new Map();
            document.querySelectorAll('h1,h2,h3,h4').forEach(h => {
                const t = (h.innerText || '').trim();
                if (!t || companyOf.has(t)) return;
                let p = h, lines = [];
                for (let i = 0; i < 6 && p; i++) {
                    p = p.parentElement;
                    if (!p) break;
                    lines = (p.innerText || '').split('\n').map(s => s.trim()).filter(Boolean);
                    if (lines.length >= 4) break;
                }
                const idx = lines.indexOf(t);
                if (idx > 0) companyOf.set(t, lines[0]);
            });
            const seen = new Set();
            const out = [];
            offers.forEach(o => {
                const id = o.url.split('/').filter(Boolean).pop() || '';
                if (!id || seen.has(id)) return;
                seen.add(id);
                out.push({
                    remote: true,
                    id: id,
                    title: o.name.slice(0, 140),
                    company: (companyOf.get(o.name) || '').slice(0, 120),
                    stack: ["__STACK__"],
                    url: o.url
                });
            });
            return JSON.stringify(out);
        })()"""
    }
}
