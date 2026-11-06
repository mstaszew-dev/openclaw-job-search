package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.mcp.McpClientException
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.worker.WorkerProperties
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Contract for the zero-argument profile_fill worker tool (2026-11-05). the
 * free-tier model burns its entire step budget re-typing the same identity
 * values into ASP.NET-style ATS forms (eRecruiter, 44 snapshot/evaluate
 * steps per theprotocol attempt); worker code owns the identity fill
 * exactly like apply_click owns the Apply click, and the model only keeps
 * form-specific fields (salary, links, experience), CV upload, and the
 * submit click.
 */
class ProfileFillerTest {

    private fun props(
        name: String = "Michal",
        surname: String = "Staszewski",
        email: String = "mst.rocking@gmail.com",
        phone: String = "+48790775407",
        city: String = "Biala Parcela",
        country: String = "Polska",
        region: String = "lodzkie",
    ) = WorkerProperties().apply {
        identity = WorkerProperties.Identity().apply {
            this.name = name
            this.surname = surname
            this.email = email
            this.phone = phone
            this.city = city
            this.country = country
            this.region = region
        }
    }

    private fun browser(
        evaluate: (() -> String)? = null,
    ): McpStdioClient = mockk {
        if (evaluate != null) {
            every { callToolText(eq("browser_evaluate"), any(), any()) } answers { evaluate() }
        }
    }

    /**
     * The REAL playwright-mcp evaluate shape (2026-11-05): the string value
     * comes back JSON-ENCODED in the Result section, and the function SOURCE
     * (full of braces) is echoed after it. The naive first-{..last-} slice
     * captured echo braces and died on the escapes - the live bug that made
     * every profile_fill report "could not parse" while the fields were
     * actually being filled.
     */
    private fun fencedReport(
        filled: List<String>,
        missing: List<String>,
        required: Int = 2,
        ticked: Int = 2,
    ): String {
        val report = mapper.writeValueAsString(
            mapOf(
                "filled" to filled,
                "missing" to missing,
                "consents" to mapOf("required" to required, "ticked" to ticked),
            ),
        )
        val encoded = report.replace("\\", "\\\\").replace("\"", "\\\"")
        return "### Result\n\"${ProfileFiller.MARKER}$encoded\"\n" +
            "### Ran Playwright code\n```js\nawait page.evaluate('() => { const out = { a: 1 }; if (x) { y } }');\n```\n" +
            "### Page\n- Page URL: https://theprotocol.it/apply\n"
    }

    private val mapper = ObjectMapper()

    @AfterEach
    fun cleanup() = clearAllMocks()

    @Test
    fun `refuses before touching the browser when identity is not configured`() {
        val browser = browser(evaluate = { fencedReport(listOf("name"), emptyList()) })

        val result = ProfileFiller(props(name = "",surname = "", email = "", phone = "", city = "", country = "", region = ""))
            .fill(browser)

        assertThat(result).startsWith("Error: identity not configured")
        assertThat(result).contains("APPLICANT_")
        verify { browser wasNot Called }
    }

    @Test
    fun `reports the page verdict with filled, missing and consents`() {
        val browser = browser(evaluate = { fencedReport(listOf("name", "surname", "email", "phone", "city", "country", "region"), emptyList(), required = 1, ticked = 1) })

        val result = ProfileFiller(props()).fill(browser)

        assertThat(result).startsWith("profile_fill: ")
        assertThat(result).contains("name, surname, email, phone, city, country, region")
        assertThat(result).contains("consents 1/1")
        assertThat(result).doesNotContain("missing")
    }

    @Test
    fun `names the missing fields so the model fills them via browser_fill_form`() {
        val browser = browser(evaluate = { fencedReport(listOf("name", "email", "phone", "country", "region"), listOf("surname", "city")) })

        val result = ProfileFiller(props()).fill(browser)

        assertThat(result).startsWith("profile_fill:")
        assertThat(result).contains("missing: surname, city")
        assertThat(result).contains("browser_fill_form")
    }

    /**
     * The 2026-11-06 theprotocol grind: the drawer has no city field, so
     * profile_fill returned missing=[city] on every call and the model,
     * told to fill the missing fields, re-called profile_fill 15 times and
     * never submitted. The verdict must warn that a missing field the form
     * does not show means the form LACKS it (skip and submit), and that a
     * repeated profile_fill always returns the same answer.
     */
    @Test
    fun `missing verdict warns the form may lack the field and profile_fill must not repeat`() {
        val browser = browser(evaluate = { fencedReport(listOf("name", "surname", "email", "phone"), listOf("city")) })

        val result = ProfileFiller(props()).fill(browser)

        assertThat(result).contains("missing: city")
        assertThat(result).contains("does not have it")
        assertThat(result).contains("NEVER call profile_fill again")
    }

    @Test
    fun `evaluate failure is an Error verdict, never a throw`() {
        val browser = browser(evaluate = { throw McpClientException("process died") })

        val result = ProfileFiller(props()).fill(browser)

        assertThat(result).startsWith("Error: profile_fill failed: ")
        assertThat(result).contains("process died")
    }

    @Test
    fun `unparseable page report is an Error verdict`() {
        val browser = browser(evaluate = { "### Error\nSyntaxError: something broke" })

        val result = ProfileFiller(props()).fill(browser)

        assertThat(result).startsWith("Error: profile_fill: could not parse the page report")
        assertThat(result).contains("SyntaxError")
    }

    @Test
    fun `the emitted JS targets eRecruiter ids, autocomplete attrs and required-only consents`() {
        val browser = browser(evaluate = { fencedReport(listOf("name", "email", "phone"), emptyList()) })
        val payloadSlot = slot<com.fasterxml.jackson.databind.JsonNode>()
        every { browser.callToolText(eq("browser_evaluate"), capture(payloadSlot), any<Duration>()) } answers { fencedReport(listOf("name", "email", "phone"), emptyList()) }

        ProfileFiller(props()).fill(browser)

        val js = payloadSlot.captured.path("function").asText()
        // eRecruiter field anchors (lowercase: compared against el.id.toLowerCase())
        assertThat(js).contains("tbname")
        assertThat(js).contains("tbsurname")
        assertThat(js).contains("tbmail")
        assertThat(js).contains("tbcellphone")
        assertThat(js).contains("lstcountries")
        assertThat(js).contains("lstregions")
        // theprotocol quick-apply drawer anchors (2026-11-05: camelCase ids
        // firstName/lastName/email/phoneNumber - only email matched via
        // type=email, the tool filled 1 of 5 fields and the model looped)
        assertThat(js).contains("firstname")
        assertThat(js).contains("lastname")
        assertThat(js).contains("phonenumber")
        // React radix drawer (theprotocol gdynia class): hashed ids, the
        // stable anchor is the label text (Imię/Nazwisko/Telefon)
        assertThat(js).contains("imię")
        assertThat(js).contains("nazwisko")
        assertThat(js).contains("telefon")
        // generic ATS anchors
        assertThat(js).contains("autocomplete")
        // the identity values travel as JSON, not as interpolated selector names
        assertThat(js).contains("Michal")
        assertThat(js).contains("mst.rocking@gmail.com")
        assertThat(js).contains("Biala Parcela")
        // consents must be REQUIRED-only: a marketing box must never be ticked
        assertThat(js).contains("required")
        // model-written JS must not be needed: bubble input+change events for SPA forms
        assertThat(js).contains("change")
    }

    @Test
    fun `objectMapper is optional dependency injection`() {
        val filler = ProfileFiller(props(), mapper = ObjectMapper())
        val browser = browser(evaluate = { fencedReport(listOf("name", "email", "phone"), emptyList()) })

        val result = filler.fill(browser)

        assertThat(result).startsWith("profile_fill:")
        verify { browser.callToolText(eq("browser_evaluate"), any(), any()) }
    }
}
