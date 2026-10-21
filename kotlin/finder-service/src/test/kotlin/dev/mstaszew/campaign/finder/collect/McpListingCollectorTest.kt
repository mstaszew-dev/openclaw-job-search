package dev.mstaszew.campaign.finder.collect

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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

    @Test
    fun `page urls follow the documented board patterns`() {
        assertThat(McpListingCollector.pageUrl("nofluffjobs", "java"))
            .isEqualTo("https://nofluffjobs.com/pl/java?criteria=cityId%3Dremote")
        assertThat(McpListingCollector.pageUrl("justjoin", "kotlin"))
            .isEqualTo("https://justjoin.it/all-remote/kotlin")
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
}
