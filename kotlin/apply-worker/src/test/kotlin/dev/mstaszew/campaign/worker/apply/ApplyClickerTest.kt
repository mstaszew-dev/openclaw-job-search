package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The 2026-11-04 NFJ runs proved the free-tier model cannot reliably emit
 * click arguments (invented refs "e1191919190", empty targets, ":Aplikuj" as
 * a CSS selector) nor valid JavaScript, so the Apply click itself must be a
 * worker-side deterministic tool with ZERO model-written arguments.
 */
class ApplyClickerTest {

    private val browser = mockk<McpStdioClient>(relaxUnitFun = true)
    private val mapper = ObjectMapper()
    private val sleeps = mutableListOf<Long>()

    private fun clicker() = ApplyClicker(sleeper = { sleeps += it })

    private fun probe(found: Boolean, disabled: Boolean = false, text: String = "Aplikuj") =
        ApplyClicker.MARKER + mapper.writeValueAsString(
            mapOf("found" to found, "tag" to "BUTTON", "disabled" to disabled, "text" to text),
        )

    private fun clickResult(url: String) =
        ApplyClicker.MARKER + mapper.writeValueAsString(mapOf("clicked" to true, "url" to url))

    @Test
    fun `clicks an enabled control and reports the tab state`() {
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns probe(found = true)
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) } returns clickResult("https://nofluffjobs.com/pl/job/x/success")
        every { browser.callToolText(eq("browser_tabs"), any(), any()) } returns
            "- 0: (current) [](about:blank)\n- 1: [Traffit](https://bluebinary.traffit.com/public/an/abc)"

        val result = clicker().click(browser, selector = null)

        assertThat(result).startsWith("apply_click: clicked")
        assertThat(result).contains("/success")
        assertThat(result).contains("traffit.com")
        assertThat(result).contains("browser_tabs")
        verify(exactly = 1) { browser.callToolText(eq("browser_tabs"), any(), any()) }
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `a disabled control is waited out then clicked`() {
        var probes = 0
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } answers {
            probes++
            probe(found = true, disabled = probes == 1)
        }
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) } returns clickResult("https://x/success")
        every { browser.callToolText(eq("browser_tabs"), any(), any()) } returns "- 0: (current) [](about:blank)"

        val result = clicker().click(browser, selector = null)

        assertThat(result).startsWith("apply_click: clicked")
        assertThat(sleeps).containsExactly(ApplyClicker.DISABLED_WAIT_MS)
        assertThat(probes).isEqualTo(2)
    }

    @Test
    fun `a permanently disabled control fails without clicking`() {
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns probe(found = true, disabled = true)

        val result = clicker().click(browser, selector = null)

        assertThat(result).startsWith("Error: apply_click:")
        assertThat(result).contains("disabled")
        verify(exactly = 0) { browser.callToolText(eq("browser_tabs"), any(), any()) }
        verify(exactly = 0) { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) }
    }

    @Test
    fun `no apply control means an Error with a snapshot hint`() {
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns probe(found = false)

        val result = clicker().click(browser, selector = null)

        assertThat(result).startsWith("Error: apply_click:")
        assertThat(result).contains("snapshot")
    }

    /** An unparseable probe (MCP noise around the marker) must fail safe, not guess. */
    @Test
    fun `a probe without the marker fails safe`() {
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns "### Error\nsome mcp failure"

        val result = clicker().click(browser, selector = null)

        assertThat(result).startsWith("Error: apply_click:")
    }

    @Test
    fun `an explicit selector is used before the text search`() {
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("const sel = \"#applyButton\"") }, any()) } returns probe(found = true)
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) } returns clickResult("https://x/success")
        every { browser.callToolText(eq("browser_tabs"), any(), any()) } returns "- 0: (current) [](about:blank)"

        val result = clicker().click(browser, selector = "#applyButton")

        assertThat(result).startsWith("apply_click: clicked")
    }

    @Test
    fun `a control that vanishes between probe and click reports the miss`() {
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns probe(found = true)
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) } returns
            ApplyClicker.MARKER + mapper.writeValueAsString(mapOf("clicked" to false))
        every { browser.callToolText(eq("browser_tabs"), any(), any()) } returns "- 0: (current) [](about:blank)"

        val result = clicker().click(browser, selector = null)

        assertThat(result).startsWith("Error: apply_click:")
        assertThat(result).contains("vanished")
    }

    /**
     * The REAL 2026-11-04 22:51 MCP capture: browser_evaluate returns the
     * string value JSON-ENCODED ("...\"found\":true..."), so a naive
     * {...} slice from the raw text dies on the backslash escapes and every
     * apply_click failed live ("Unexpected character ('\')"). The extractor
     * must decode the enclosing string before parsing.
     */
    @Test
    fun `parses the real mcp shape where the value comes back json encoded`() {
        val encodedResult = "### Result\n" +
            "\"APPLY_PROBE_MARKER_7f3a{\\\"found\\\":true,\\\"tag\\\":\\\"BUTTON\\\",\\\"disabled\\\":false,\\\"text\\\":\\\"Aplikuj\\\"}\"\n" +
            "### Ran Playwright code\n```js\nawait page.evaluate('() => probe()');\n```\n" +
            "### Page\n- Page URL: https://nofluffjobs.com/pl/job/x\n- Console: 0 errors, 7 warnings\n"
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns encodedResult
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) } returns
            "### Result\n" +
            "\"APPLY_PROBE_MARKER_7f3a{\\\"clicked\\\":true,\\\"url\\\":\\\"https://nofluffjobs.com/pl/job/x/success\\\"}\"\n" +
            "### Ran Playwright code\n```js\nawait page.evaluate('() => click()');\n```\n"
        every { browser.callToolText(eq("browser_tabs"), any(), any()) } returns "- 0: (current) [](about:blank)"

        val result = clicker().click(browser, selector = null)

        assertThat(result).startsWith("apply_click: clicked")
        assertThat(result).contains("/success")
    }

    @Test
    fun `extractor decodes bare and json-encoded marker values`() {
        val bare = "### Result\nAPPLY_PROBE_MARKER_7f3a{\"found\":false}\n"
        val encoded = "### Result\n\"APPLY_PROBE_MARKER_7f3a{\\\"found\\\":true,\\\"tag\\\":\\\"A\\\"}\"\n"
        val clicker = clicker()
        assertThat(clicker.extractProbeJson(bare)?.path("found")?.asBoolean()).isFalse()
        val decoded = clicker.extractProbeJson(encoded)
        assertThat(decoded?.path("found")?.asBoolean()).isTrue()
        assertThat(decoded?.path("tag")?.asText()).isEqualTo("A")
    }

    /**
     * Pins the escaping contract of the generated JS: the regex must arrive
     * with a real `$` (the Kotlin `\$` template escape), the marker must be
     * part of the returned VALUE, and a bad model selector must degrade to
     * the label search instead of killing the evaluate.
     */
    @Test
    fun `emitted probe javascript pins the escaping contract`() {
        val fnSlot = io.mockk.slot<com.fasterxml.jackson.databind.JsonNode>()
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns probe(found = false)

        clicker().click(browser, selector = null)

        verify { browser.callToolText(eq("browser_evaluate"), capture(fnSlot), any()) }
        val js = fnSlot.captured.path("function").asText()
        assertThat(js).contains("/^(aplikuj|zaaplikuj|apply|quick apply)/i")
        assertThat(js).contains("try { el = sel && sel.length ? document.querySelector(sel) : null; } catch (e) { el = null; }")
        assertThat(js).contains("return '${ApplyClicker.MARKER}' + JSON.stringify({found:true")
    }
}
