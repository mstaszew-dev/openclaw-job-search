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
}
