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
    fun `clicks an enabled control, switches to the opened ATS tab and reports it`() {
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns probe(found = true)
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) } returns clickResult("https://nofluffjobs.com/pl/job/x")
        var clickHappened = false
        every { browser.callToolText(eq("browser_tabs"), any(), any()) } answers {
            val args = secondArg<com.fasterxml.jackson.databind.JsonNode>()
            if (args.path("action").asText() == "select") "selected tab 1"
            else if (clickHappened) "- 0: [](https://nofluffjobs.com/pl/job/x)\n- 1: [Traffit](https://bluebinary.traffit.com/public/an/abc)"
            else "- 0: (current) [](https://nofluffjobs.com/pl/job/x)"
        }
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) } answers {
            clickHappened = true
            clickResult("https://nofluffjobs.com/pl/job/x")
        }

        val result = clicker().click(browser, selector = null)

        assertThat(result).startsWith("apply_click: clicked")
        assertThat(result).contains("SWITCHED")
        assertThat(result).contains("traffit.com")
        verify(exactly = 1) {
            browser.callToolText(eq("browser_tabs"), match { it.path("action").asText() == "select" && it.path("index").asInt() == 1 }, any())
        }
        assertThat(sleeps).isEmpty()
    }

    /**
     * 2026-11-05 inzynier live run: apply_click opened the ATS tab but the
     * model never called browser_tabs(select) - it ground snapshots on the
     * listing until the breaker killed it. The model demonstrably will not do
     * the switch, so the worker owns it: diff the tab list across the click
     * and select the NEW tab deterministically.
     */
    @Test
    fun `auto-select names the new tab index in the answer`() {
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns probe(found = true)
        var clickHappened = false
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) } answers {
            clickHappened = true
            clickResult("https://nofluffjobs.com/pl/job/x")
        }
        every { browser.callToolText(eq("browser_tabs"), any(), any()) } answers {
            val args = secondArg<com.fasterxml.jackson.databind.JsonNode>()
            if (args.path("action").asText() == "select") "selected tab 3"
            else if (clickHappened) "- 0: [](https://x)\n- 2: [](https://gmail.com)\n- 3: [ATS](https://ats.example/form)"
            else "- 0: (current) [](https://x)\n- 2: [](https://gmail.com)"
        }

        val result = clicker().click(browser, selector = null)

        assertThat(result).contains("SWITCHED")
        assertThat(result).contains("index 3")
        verify(exactly = 1) {
            browser.callToolText(eq("browser_tabs"), match { it.path("action").asText() == "select" && it.path("index").asInt() == 3 }, any())
        }
    }

    /** No new tab (same-tab redirect): no switch, plain report. */
    @Test
    fun `no new tab means no switch`() {
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns probe(found = true)
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) } returns clickResult("https://x/success")
        every { browser.callToolText(eq("browser_tabs"), any(), any()) } returns "- 0: [](https://x/success)"
        every { browser.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains(ApplyClicker.FORM_CHECK_MARKER) }, any()) } returns
            ApplyClicker.FORM_CHECK_MARKER + mapper.writeValueAsString(mapOf("inputs" to 1))

        val result = clicker().click(browser, selector = null)

        assertThat(result).doesNotContain("SWITCHED")
        assertThat(result).doesNotContain("QUICK-APPLY FORM IS OPEN")
        verify(exactly = 0) { browser.callToolText(eq("browser_tabs"), match { it.path("action").asText() == "select" }, any()) }
    }

    /**
     * 2026-11-05 expert-poznan live run: theprotocol's quick-apply opens a
     * SAME-TAB drawer (no tab switch possible), the model re-clicked apply 5x
     * instead of filling it. After the click, apply_click must detect the
     * opened form and hand the model the fill recipe.
     */
    @Test
    fun `a same-tab drawer form is detected and the model is told to fill it`() {
        // A FRESH mock: this test needs exactly four evaluate shapes, and
        // stubs inherited from sibling tests polluted the form-check call
        // (the shared mock re-stubs additively across tests in one class).
        val local = mockk<McpStdioClient>(relaxUnitFun = true)
        every { local.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("APPLY_PROBE_MARKER") }, any()) } returns probe(found = true)
        every { local.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains("el.click()") }, any()) } returns clickResult("https://x/listing")
        every { local.callToolText(eq("browser_tabs"), any(), any()) } returns "- 0: [](https://x/listing)"
        every { local.callToolText(eq("browser_evaluate"), match { it.path("function").asText().contains(ApplyClicker.FORM_CHECK_MARKER) }, any()) } returns
            ApplyClicker.FORM_CHECK_MARKER + mapper.writeValueAsString(mapOf("inputs" to 5))

        val result = clicker().click(local, selector = null)

        assertThat(result).contains("QUICK-APPLY FORM IS OPEN")
        assertThat(result).contains("profile_fill")
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
