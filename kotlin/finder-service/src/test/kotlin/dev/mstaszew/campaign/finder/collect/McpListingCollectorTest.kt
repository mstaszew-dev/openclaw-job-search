package dev.mstaszew.campaign.finder.collect

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.mcp.McpClientException
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class McpListingCollectorTest {

    @Test
    fun `parses result block with echoed code around it`() {
        val text = "### Result\n" +
            "\"[{\\\"remote\\\":true,\\\"id\\\":\\\"9\\\",\\\"title\\\":\\\"Dev\\\",\\\"company\\\":\\\"C\\\",\\\"url\\\":\\\"https://x.com/9\\\"}]\"\n" +
            "### Ran Playwright code\n```js\nawait page.evaluate('() => (function(){ return [...[1]]; })()')\n```"

        val listings = McpListingCollector({ throw IllegalStateException() })
            .let { it.parseForTest(text, "nofluffjobs") }

        assertThat(listings).hasSize(1)
        assertThat(listings[0].sourceJobId).isEqualTo("9")
    }

    @Test
    fun `parses embedded json array from mcp text output`() {
        val text = "some tool preamble\n" +
            """[{"remote":true,"id":"7","title":"Java Dev","company":"Acme Ltd","url":"https://x.com/7","salaryMin":18000,"stack":["java"]}]""" +
            "\ntrailing noise"

        val listings = McpListingCollector({ throw IllegalStateException("no browser in unit tests") })
            .let { it.parseForTest(text, "nofluffjobs") }

        assertThat(listings).hasSize(1)
        val first = listings[0]
        assertThat(first.sourceJobId).isEqualTo("7")
        assertThat(first.company).isEqualTo("Acme Ltd")
        assertThat(first.remotePolicy).isEqualTo("remote")
        assertThat(first.salaryMin).isEqualByComparingTo("18000")
    }

    @Test
    fun `listings without explicit remote flag fail closed`() {
        val text = """[{"id":"8","title":"Hybrid Dev","company":"Beta","url":"https://x.com/8"}]"""

        val listings = McpListingCollector({ throw IllegalStateException() })
            .let { it.parseForTest(text, "justjoin") }

        assertThat(listings).isEmpty()
    }

    @Test
    fun `no array in output yields empty batch`() {
        val listings = McpListingCollector({ throw IllegalStateException() })
            .let { it.parseForTest("boom", "nofluffjobs") }
        assertThat(listings).isEmpty()
    }

    /**
     * Board card hrefs carry session state in the query (theprotocol appends
     * ?remote=true&s=...&searchId=<uuid> on every render), and the card id
     * was the raw last path segment including that query: one listing minted
     * a new job id per window, three grinds and three job_state rows for one
     * requisition (andrychow, 2026-10-23..24). The source id is the path
     * slug; anything from '?' or '#' on is session state.
     */
    @Test
    fun `card ids shed volatile query params into a stable source id`() {
        val text = """[{"remote":true,"id":"wielicka-28,oferta,01000000-36eb?remote=true&s=-1&searchId=abc","title":"Senior Software Engineer (React)","company":"New Co","url":"https://theprotocol.it/praca/wielicka-28,oferta,01000000-36eb?remote=true&searchId=abc"},""" +
            """{"remote":true,"id":"?remote=true&searchId=only-query","title":"Query Only","company":"C","url":"https://theprotocol.it/praca/x?remote=true"}]"""

        val listings = McpListingCollector({ throw IllegalStateException() })
            .let { it.parseForTest(text, "theprotocol") }

        // The query-only id strips to blank and fails closed rather than
        // enqueuing a job with an empty identity.
        assertThat(listings).hasSize(1)
        assertThat(listings.single().sourceJobId).isEqualTo("wielicka-28,oferta,01000000-36eb")
    }

    @Test
    fun `page urls follow the documented board patterns`() {
        assertThat(McpListingCollector.pageUrl("nofluffjobs", "java"))
            .isEqualTo("https://nofluffjobs.com/pl/java?criteria=cityId%3Dremote")
        assertThat(McpListingCollector.pageUrl("justjoin", "java"))
            .isEqualTo("https://justjoin.it/job-offers/remote/java")
    }

    /**
     * 2026-10-26 inflow expansion: the five original stack queries per board
     * are 100% swept (every window: seen == dupWindow + deduped + ineligible
     * + lowScore, enqueued 0). NFJ serves real category pages for spring,
     * typescript and javascript (200, verified); justjoin has no such remote
     * categories (404, verified) so they fold to the nearest real chip.
     */
    @Test
    fun `nofluffjobs serves the expanded stacks as direct queries`() {
        assertThat(McpListingCollector.pageUrl("nofluffjobs", "spring"))
            .isEqualTo("https://nofluffjobs.com/pl/spring?criteria=cityId%3Dremote")
        assertThat(McpListingCollector.pageUrl("nofluffjobs", "typescript"))
            .isEqualTo("https://nofluffjobs.com/pl/typescript?criteria=cityId%3Dremote")
        assertThat(McpListingCollector.pageUrl("nofluffjobs", "javascript"))
            .isEqualTo("https://nofluffjobs.com/pl/javascript?criteria=cityId%3Dremote")
    }

    /**
     * spring and typescript are not justjoin remote categories and NOT
     * theprotocol chips (both /filtry pages fall back to the generic offer
     * pool - verified in a real browser 2026-10-21); folding to the nearest
     * real chip avoids scraping the same general pool twice per window.
     */
    @Test
    fun `spring and typescript fold to real chips on justjoin and theprotocol`() {
        assertThat(McpListingCollector.pageUrl("justjoin", "spring"))
            .isEqualTo("https://justjoin.it/job-offers/remote/java")
        assertThat(McpListingCollector.pageUrl("justjoin", "typescript"))
            .isEqualTo("https://justjoin.it/job-offers/remote/javascript")
        assertThat(McpListingCollector.pageUrl("theprotocol", "spring"))
            .isEqualTo("https://theprotocol.it/filtry/java?remote=true")
        assertThat(McpListingCollector.pageUrl("theprotocol", "typescript"))
            .isEqualTo("https://theprotocol.it/filtry/javascript?remote=true")
        // original stacks unchanged
        assertThat(McpListingCollector.pageUrl("theprotocol", "java"))
            .isEqualTo("https://theprotocol.it/filtry/java?remote=true")
        assertThat(McpListingCollector.pageUrl("justjoin", "kotlin"))
            .isEqualTo("https://justjoin.it/job-offers/remote/java")
    }

    /**
     * The default stack list carries the expansion: eight queries per
     * window; NFJ scrapes all eight pages, justjoin folds to three
     * (java, javascript, php) and theprotocol to its real chips (java,
     * javascript, php, plus the historical kotlin/node pages).
     */
    @Test
    fun `default stacks include the expansion`() {
        val stacks = McpListingCollector({ throw IllegalStateException() }).let { it }.stacksForTest()
        assertThat(stacks).containsExactly(
            "java", "kotlin", "spring", "php", "node", "react", "javascript", "typescript",
        )
    }

    /**
     * justjoin paginates: /job-offers/remote/java?page=2 serves a genuinely
     * different set of 50 offers (verified in a real browser 2026-10-26:
     * page 2 showed j-labs/devsdata, none of page 1), and the board's meta
     * claims 738 remote offers vs the ~150 we scraped - page 2 alone doubles
     * the per-category pool. The other boards have no verified page param
     * yet, so they stay at one page.
     */
    @Test
    fun `justjoin scrapes page two of every category`() {
        val client = mockk<McpStdioClient>(relaxUnitFun = true) {
            every { callToolText(any(), any(), any()) } returns "### Result\n[]"
        }

        McpListingCollector({ client }).collect("justjoin")

        verify(exactly = 1) {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "https://justjoin.it/job-offers/remote/java?page=2" },
                any(),
            )
        }
        verify(exactly = 1) {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "https://justjoin.it/job-offers/remote/javascript?page=2" },
                any(),
            )
        }
        verify(exactly = 1) {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "https://justjoin.it/job-offers/remote/php?page=2" },
                any(),
            )
        }
        verify(exactly = 6) {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText().contains("justjoin.it") },
                any(),
            )
        }
    }

    @Test
    fun `nofluffjobs and theprotocol stay at one page per query`() {
        val client = mockk<McpStdioClient>(relaxUnitFun = true) {
            every { callToolText(any(), any(), any()) } returns "### Result\n[]"
        }

        McpListingCollector({ client }, stacks = listOf("java")).collect("theprotocol")

        verify(exactly = 1) {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText().contains("theprotocol.it") },
                any(),
            )
        }
    }

    /**
     * justjoin's 2025+ taxonomy has no remote categories for kotlin, node or
     * react: /job-offers/remote/{kotlin,node,react} are 404 (checked
     * 2026-10-25). Kotlin roles post under java, node and react under
     * javascript, so map them onto categories that exist rather than
     * navigating to a 404 page every window.
     */
    @Test
    fun `justjoin maps stacks without a remote category onto the nearest one`() {
        assertThat(McpListingCollector.pageUrl("justjoin", "kotlin"))
            .isEqualTo("https://justjoin.it/job-offers/remote/java")
        assertThat(McpListingCollector.pageUrl("justjoin", "node"))
            .isEqualTo("https://justjoin.it/job-offers/remote/javascript")
        assertThat(McpListingCollector.pageUrl("justjoin", "react"))
            .isEqualTo("https://justjoin.it/job-offers/remote/javascript")
        assertThat(McpListingCollector.pageUrl("justjoin", "php"))
            .isEqualTo("https://justjoin.it/job-offers/remote/php")
    }

    /**
     * The default stack list folds java+kotlin onto java and node+react onto
     * javascript; the second visit to a category would only burn a settle
     * wait and inflate dupWindow, so one window scrapes each justjoin
     * category exactly once.
     */
    @Test
    fun `collect scrapes each justjoin category once per window`() {
        val client = mockk<McpStdioClient>(relaxUnitFun = true) {
            every { callToolText(any(), any(), any()) } returns "### Result\n[]"
        }

        McpListingCollector({ client }).collect("justjoin")

        listOf(
            "https://justjoin.it/job-offers/remote/java",
            "https://justjoin.it/job-offers/remote/javascript",
            "https://justjoin.it/job-offers/remote/php",
        ).forEach { url ->
            verify(exactly = 1) {
                client.callToolText(
                    eq("browser_navigate"),
                    match { it.path("url").asText() == url },
                    any(),
                )
            }
        }
        // No unexpected extra justjoin page beyond the three categories x
        // two pages (page 2 added 2026-10-26).
        verify(exactly = 6) {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText().contains("justjoin.it") },
                any(),
            )
        }
    }

    /**
     * justjoin's 2025 redesign killed /all-remote (404 ever since; the board
     * contributed zero listings the whole campaign - job_listings has no
     * justjoin docs at all). The new listing page has no anchor cards (cards
     * are MUI divs), but it server-renders a JSON-LD ItemList with every
     * offer URL and title, and the hydrated cards carry the company as their
     * first text line. The extractor joins the two by title. The scraped
     * category is emitted as the stack so titles without a stack word
     * ("Guidewire Policy Center Developer" on /remote/java) still pass the
     * eligibility stack gate.
     */
    @Test
    fun `justjoin extraction reads the json-ld item list instead of card anchors`() {
        val script = McpListingCollector.extractionScript("justjoin", "kotlin")
        assertThat(script).contains("application/ld+json")
        assertThat(script).contains("itemListElement")
        assertThat(script).contains("/job-offer/")
        assertThat(script).contains("stack: [\"java\"]")
        assertThat(script).doesNotContain("__SELECTOR__")
        assertThat(script).doesNotContain("chipNoise")
        assertThat(script).doesNotContain("__STACK__")
    }

    @Test
    fun `badge alternation covers the observed board chips`() {
        // 2026-10-21: these exact strings were scraped as companies; the
        // whole-line chip filter must know every one of them.
        val alt = McpListingCollector.chipBadgeAlternation()
        listOf(
            "new", "nowa", "nowe", "viewed", "start asap", "quick apply",
            "b2b", "uop", "backend", "frontend", "fullstack", "mobile",
            "sprawdź wynagrodzenie", "sprawdzisz zarobki", "ai ml", "remote",
        ).forEach { chip ->
            assertThat(alt).contains(chip)
        }
    }

    @Test
    fun `alternation matches chip lines and never real company lines`() {
        // JVM-engine semantics check over the same words the browser JS gets:
        // strip the JS-only slash escaping first (Java regex rejects "\/").
        val pattern = Regex(
            "^(?:${McpListingCollector.chipBadgeAlternation().replace("\\/", "/")})$",
            RegexOption.IGNORE_CASE,
        )
        listOf(
            "NEW", "B2B", "Start ASAP", "AI/ML", "Sprawdź wynagrodzenie",
            "Remote", "ML", "Hybrid", "Quick Apply",
        ).forEach { chip ->
            assertThat(pattern.matches(chip)).describedAs("chip line %s", chip).isTrue()
        }
        listOf("B2B Group", "Link Group", "DataArt", "Nowe horyzonty").forEach { company ->
            assertThat(pattern.matches(company)).describedAs("company line %s", company).isFalse()
        }
    }

    @Test
    fun `extraction scripts embed the whole line chip filter`() {
        // anchored alternation (^...$) so real companies like "B2B Group"
        // survive; the legacy prefix rules stay untouched. The slash in the
        // AI/ML branch must be JS-escaped or the regex literal (and with it
        // every extraction) dies with a SyntaxError.
        listOf("nofluffjobs", "theprotocol").forEach { source ->
            val script = McpListingCollector.extractionScript(source)
            assertThat(script).contains("chipNoise = /^(new|nowa|")
            assertThat(script).contains("chipNoise.test(l)")
            assertThat(script).contains("ai\\/ml")
            // the unescaped slash would terminate the JS regex literal early
            assertThat(script).doesNotContain("ai/ml")
        }
    }

    /**
     * Tab hygiene: the board page (and its OOPIF iframes - hubspot chat, ad
     * trackers) outlives the MCP session, and those targets stall every later
     * playwright-mcp connectOverCDP at init (the 2026-10-22 wedge:
     * "initializeServer timeout" with ws connected). Each collect must blank
     * its tab on the way out.
     */
    @Test
    fun `collect blanks the tab afterwards so leftover iframes cannot wedge later spawns`() {
        val client = cleanupClient()

        McpListingCollector({ client }, stacks = listOf("java", "react")).collect("nofluffjobs")

        verify(exactly = 1) {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
        }
    }

    /**
     * Full unit-end cleanup, beyond blanking: board pages open target=_blank
     * tabs and leave OOPIF orphans alive on every tab they touch (failure
     * modes 7/8). The collect-end cleanup mirrors the worker's shipped
     * semantics (commit f99c8f0): blank the CURRENT tab first - it stays the
     * session's live page as the survivor - then close every non-survivor
     * descending so a close never renumbers a pending one. Tabs only: portal
     * logins live in the profile cookie jar, untouched.
     */
    private fun cleanupClient(): McpStdioClient = mockk<McpStdioClient>(relaxUnitFun = true) {
        every { callToolText(any(), any(), any()) } returns "### Result\n[]"
    }

    private fun stubTabList(client: McpStdioClient, vararg lines: String) {
        every {
            client.callToolText(eq("browser_tabs"), match { it.path("action").asText() == "list" }, any())
        } returns (listOf("### Result") + lines.toList()).joinToString("\n")
    }

    private fun MockKMatcherScope.closeTab(index: Int) =
        match<JsonNode> {
            it.path("action").asText() == "close" && it.path("index").asInt() == index
        }

    @Test
    fun `collect end closes portal-opened tabs, keeping the blanked survivor`() {
        val client = cleanupClient()
        stubTabList(
            client,
            "- 0: (current) [](about:blank)",
            "- 1: [Apply - Dev](https://nofluffjobs.com/pl/job/x)",
            "- 2: [Listing](https://justjoin.it/offers/y)",
        )

        val listings = McpListingCollector({ client }, stacks = listOf("java")).collect("nofluffjobs")

        assertThat(listings).isEmpty()
        verifyOrder {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
            client.callToolText(eq("browser_tabs"), closeTab(2), any())
            client.callToolText(eq("browser_tabs"), closeTab(1), any())
        }
        verify(exactly = 0) { client.callToolText(eq("browser_tabs"), closeTab(0), any()) }
    }

    @Test
    fun `the survivor is the session's current tab even when blanking failed`() {
        val client = cleanupClient()
        stubTabList(
            client,
            "- 0: (current) [Listing](https://justjoin.it/offers/y)",
            "- 1: [](about:blank)",
        )
        every {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
        } throws RuntimeException("wedge")

        McpListingCollector({ client }, stacks = listOf("java")).collect("nofluffjobs")

        verify { client.callToolText(eq("browser_tabs"), closeTab(1), any()) }
        verify(exactly = 0) { client.callToolText(eq("browser_tabs"), closeTab(0), any()) }
    }

    /** No (current) marker at all: the lowest-index tab is the survivor. */
    @Test
    fun `without a current marker the lowest index survives`() {
        val client = cleanupClient()
        stubTabList(
            client,
            "- 0: [](about:blank)",
            "- 1: [Listing](https://justjoin.it/offers/y)",
        )

        McpListingCollector({ client }, stacks = listOf("java")).collect("nofluffjobs")

        verify { client.callToolText(eq("browser_tabs"), closeTab(1), any()) }
        verify(exactly = 0) { client.callToolText(eq("browser_tabs"), closeTab(0), any()) }
    }

    /** A garbage index line must be dropped, never able to throw out of the
     *  finally block (best-effort contract). */
    @Test
    fun `an absurd tab index does not break the cleanup or the collect`() {
        val client = cleanupClient()
        stubTabList(
            client,
            "- 0: (current) [](about:blank)",
            "- 99999999999: [weird](https://x.invalid/w)",
        )

        val listings = McpListingCollector({ client }, stacks = listOf("java")).collect("nofluffjobs")

        assertThat(listings).isEmpty()
        verify {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
        }
    }

    @Test
    fun `a wedged tab list still blanks the tab`() {
        val client = cleanupClient()
        every { client.callToolText(eq("browser_tabs"), any(), any()) } throws
            RuntimeException("initializeServer: Timeout 5000ms exceeded")

        McpListingCollector({ client }, stacks = listOf("java")).collect("nofluffjobs")

        verify(exactly = 1) {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
        }
    }

    @Test
    fun `one failing tab close does not stop the remaining closes`() {
        val client = cleanupClient()
        stubTabList(
            client,
            "- 0: (current) [](about:blank)",
            "- 1: [Apply form](https://nofluffjobs.com/pl/job/x)",
            "- 2: [Listing](https://justjoin.it/offers/y)",
        )
        every { client.callToolText(eq("browser_tabs"), closeTab(2), any()) } throws RuntimeException("poof")

        val listings = McpListingCollector({ client }, stacks = listOf("java")).collect("nofluffjobs")

        assertThat(listings).isEmpty()
        verify { client.callToolText(eq("browser_tabs"), closeTab(1), any()) }
        verify {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
        }
    }

    @Test
    fun `tab closes are capped at five per collect`() {
        val client = cleanupClient()
        stubTabList(
            client,
            "- 0: (current) [](about:blank)",
            "- 1: [t1](https://x.invalid/1)",
            "- 2: [t2](https://x.invalid/2)",
            "- 3: [t3](https://x.invalid/3)",
            "- 4: [t4](https://x.invalid/4)",
            "- 5: [t5](https://x.invalid/5)",
            "- 6: [t6](https://x.invalid/6)",
            "- 7: [t7](https://x.invalid/7)",
            "- 8: [t8](https://x.invalid/8)",
        )

        McpListingCollector({ client }, stacks = listOf("java")).collect("nofluffjobs")

        verify(exactly = 5) { client.callToolText(eq("browser_tabs"), match { it.path("action").asText() == "close" }, any()) }
        verify(exactly = 1) { client.callToolText(eq("browser_tabs"), closeTab(4), any()) }
        verify(exactly = 0) { client.callToolText(eq("browser_tabs"), closeTab(3), any()) }
    }

    /**
     * The documented per-stack resilience contract: one bad board page must
     * never block the run. A wedged stack is logged and skipped; the other
     * stacks still harvest and the tab cleanup still runs.
     */
    @Test
    fun `one wedged stack does not block the other stacks`() {
        val client = cleanupClient()
        every {
            client.callToolText(eq("browser_navigate"), match { it.path("url").asText().contains("/php") }, any())
        } throws McpClientException("initializeServer: Timeout 5000ms")
        val listingJson =
            """[{"remote":true,"id":"r1","title":"React Dev","company":"Acme","url":"https://x.com/r1"}]"""
        // stacks iterate in order: java evaluates first (empty), php wedges at
        // navigate so never evaluates, react evaluates second (the listing).
        every {
            client.callToolText(eq("browser_evaluate"), any(), any())
        } returnsMany listOf(
            "### Result\n[]",
            "### Result\n" + ObjectMapper().writeValueAsString(listingJson),
        )

        val listings = McpListingCollector({ client }, stacks = listOf("java", "php", "react")).collect("nofluffjobs")

        assertThat(listings).hasSize(1)
        assertThat(listings[0].sourceJobId).isEqualTo("r1")
        verify {
            client.callToolText(eq("browser_navigate"), match { it.path("url").asText().contains("/react") }, any())
        }
        verify(exactly = 1) {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
        }
    }

    @Test
    fun `an unexpected stack failure aborts the whole source`() {
        val client = cleanupClient()
        every {
            client.callToolText(eq("browser_navigate"), match { it.path("url").asText().contains("/php") }, any())
        } throws IllegalStateException("board redesign")

        val thrown = runCatching {
            McpListingCollector({ client }, stacks = listOf("java", "php", "react")).collect("nofluffjobs")
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        // cleanup on EVERY exit path, including the aborting one
        verify {
            client.callToolText(
                eq("browser_navigate"),
                match { it.path("url").asText() == "about:blank" },
                any(),
            )
        }
    }

    /**
     * The settle wait is a fixed 3s sleep whose result is discarded; bound it
     * well below the 90s tool timeout so a wedged wait_for cannot eat most of
     * a 15-min finder window across five stacks.
     */
    @Test
    fun `the settle wait is bounded well below the tool timeout`() {
        val client = cleanupClient()

        McpListingCollector({ client }, stacks = listOf("java")).collect("nofluffjobs")

        verify {
            client.callToolText(eq("browser_wait_for"), any(), eq(Duration.ofSeconds(10)))
        }
    }
}
