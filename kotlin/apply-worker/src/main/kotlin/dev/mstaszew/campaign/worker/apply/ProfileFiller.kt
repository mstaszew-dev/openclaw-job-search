package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.worker.WorkerProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Zero-argument, worker-side identity fill (2026-11-05). The live theprotocol
 * runs of that day showed the free-tier model burning its whole 200-step
 * budget on what is, mechanically, the same ASP.NET contact block every
 * time: eRecruiter's tbName/tbSurname/tbMail/tbCellPhone plus the country +
 * region selects and the required-consent checkboxes - 44+ snapshot and
 * evaluate steps per attempt, capping out without ever reaching submit.
 *
 * This tool mirrors apply_click's worker-side pattern: the model calls
 * profile_fill() with NO arguments and NO JavaScript; the worker authors the
 * evaluate payload, fills the canonical identity values (name, surname,
 * email, phone, city, country select, voivodeship select, required
 * consents), dispatches input+change events for SPA frameworks, and returns
 * the page's own verdict (filled / missing / consents). The model keeps what
 * is genuinely per-portal: extra form fields, CV upload, the submit click.
 *
 * Never throws: every failure is an "Error: ..." verdict so the model can
 * fall back to browser_fill_form with real snapshot refs, or skip_task.
 */
@Component
class ProfileFiller(
    private val props: WorkerProperties,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    private val log = LoggerFactory.getLogger(ProfileFiller::class.java)

    fun fill(browser: McpStdioClient): String {
        val id = props.identity
        if (id.name.isBlank() && id.surname.isBlank() && id.email.isBlank() && id.phone.isBlank()) {
            return "Error: identity not configured (set APPLICANT_NAME / APPLICANT_SURNAME / APPLICANT_EMAIL / " +
                "APPLICANT_PHONE in worker.env); fill the identity fields manually via browser_fill_form"
        }

        val identityJson = mapper.writeValueAsString(
            mapOf(
                "name" to id.name,
                "surname" to id.surname,
                "email" to id.email,
                "phone" to id.phone,
                "city" to id.city,
                "country" to id.country,
                "region" to id.region,
            ),
        ).replace("'", "\\u0027")

        val js = PROFILE_JS.replace(IDENTITY_PLACEHOLDER, identityJson)
        val args = mapper.createObjectNode().apply { put("function", js) }

        val raw = try {
            browser.callToolText("browser_evaluate", args, TOOL_TIMEOUT)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            return "Error: profile_fill failed: ${e.message?.take(250)}"
        }

        val report = McpEvaluateJson.extract(mapper, raw, MARKER)
            ?: run {
                // Silent parse failures were undebuggable live (2026-11-05:
                // every profile_fill "could not parse" with no trace of why).
                log.warn("profile_fill: marker/JSON extraction failed; raw: {}", raw.take(300))
                return "Error: profile_fill: could not parse the page report (${raw.take(250)})"
            }

        val filled = report.path("filled").asTextList()
        val missing = report.path("missing").asTextList()
        val consents = report.path("consents")

        val verdict = StringBuilder("profile_fill: ")
        if (filled.isEmpty()) verdict.append("nothing filled") else verdict.append(filled.joinToString(", "))
        if (missing.isNotEmpty()) {
            verdict.append("; missing: ").append(missing.joinToString(", "))
            verdict.append(" (fill these via browser_fill_form with real refs from a fresh snapshot)")
        }
        if (consents.isObject) {
            val ticked = consents.path("ticked").asInt(0)
            val required = consents.path("required").asInt(0)
            verdict.append("; consents ").append(ticked).append("/").append(required)
            if (required > 0 && ticked < required) verdict.append(" (REVIEW the remaining consents before submit)")
        }
        log.info("profile_fill: filled={} missing={}", filled, missing)
        return verdict.toString()
    }

    private fun com.fasterxml.jackson.databind.JsonNode.asTextList(): List<String> =
        if (isArray) elements().asSequence().map { it.asText() }.toList() else emptyList()

    companion object {
        val TOOL_TIMEOUT: Duration = Duration.ofSeconds(120)

        /**
         * Marker prefixing the page report VALUE (see McpEvaluateJson): the
         * JSON-encoded MCP result and the brace-laden source echo make naive
         * slicing unreliable; the marker anchors every occurrence.
         */
        const val MARKER = "PF_REPORT_5a91"

        private const val IDENTITY_PLACEHOLDER = "%%IDENTITY%%"

        /**
         * Worker-authored JS. Anchors, in priority order:
         *  - eRecruiter (theprotocol/pracuj family): id-suffix tbName,
         *    tbSurname, tbMail, tbCellPhone, tbCity, lstCountries, lstRegions;
         *  - generic ATS: autocomplete="given-name|family-name|email|tel".
         * Selects are matched by visible option text, not index, and are
         * skipped when neither exact nor contains match exists. Consents are
         * REQUIRED-ONLY (checked/aria-required); marketing boxes are left
         * alone. input+change events bubble for React/Vue/Angular forms, and
         * ASP.NET postbacks read .value directly, so both worlds work.
         */
        private val PROFILE_JS = """
() => {
  const ID = JSON.parse('%%IDENTITY%%');
  const out = { filled: [], missing: [], consents: { required: 0, ticked: 0 } };
  const visible = (e) => e.offsetParent !== null || e.type === 'hidden';
  const all = Array.from(document.querySelectorAll('input, select, textarea')).filter(visible);
  const setNative = (el, kind, value) => {
    const proto = el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    const desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) { desc.set.call(el, value); } else { el.value = value; }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    out.filled.push(kind);
  };
  const textSel = (el, want) => {
    const options = Array.from(el.options);
    const exact = options.find(o => o.text.trim().toLowerCase() === want.trim().toLowerCase());
    const loose = options.find(o => o.text.trim().toLowerCase().includes(want.trim().toLowerCase()));
    const opt = exact || loose;
    if (!opt) return false;
    setNative(el, el.name || el.id, opt.value || opt.text);
    return true;
  };
  const auto = (e, a) => (e.getAttribute('autocomplete') || '').toLowerCase() === a;
  const idSuffix = (e, s) => (e.id || '').toLowerCase().endsWith(s);
  const idOrName = (e, f) => (e.id || '').toLowerCase().includes(f.toLowerCase()) || (e.name || '').toLowerCase().includes(f.toLowerCase());
  const taken = new Set();
  const fillText = (kind, matches, value) => {
    if (!value) return;
    const el = all.find(e => e.tagName !== 'SELECT' && e.type !== 'checkbox' && e.type !== 'radio'
      && !taken.has(e) && matches.some(m => m(e)));
    if (!el) { out.missing.push(kind); return; }
    taken.add(el); setNative(el, kind, value);
  };
  fillText('name', [e => idSuffix(e, 'tbname'), e => auto(e, 'given-name'), e => idSuffix(e, 'tbfirstname'), e => idSuffix(e, 'firstname')], ID.name);
  fillText('surname', [e => idSuffix(e, 'tbsurname'), e => auto(e, 'family-name'), e => idSuffix(e, 'tblastname'), e => idSuffix(e, 'lastname')], ID.surname);
  fillText('email', [e => idSuffix(e, 'tbmail'), e => auto(e, 'email'), e => (e.type || '') === 'email'], ID.email);
  fillText('phone', [e => idSuffix(e, 'tbcellphone'), e => auto(e, 'tel'), e => idSuffix(e, 'phonenumber')], ID.phone);
  fillText('city', [e => idSuffix(e, 'tbcity'), e => auto(e, 'address-level2'), e => idOrName(e, 'city')], ID.city);
  const country = all.find(e => e.tagName === 'SELECT' && (idSuffix(e, 'lstcountries') || idOrName(e, 'country')));
  const region = all.find(e => e.tagName === 'SELECT' && (idSuffix(e, 'lstregions') || idOrName(e, 'region') || idOrName(e, 'state')));
  if (ID.country && country && textSel(country, ID.country)) {} else if (ID.country && country) out.missing.push('country[' + ID.country + ']');
  if (ID.region && region && textSel(region, ID.region)) {} else if (ID.region && region) out.missing.push('region[' + ID.region + ']');
  const requiredBoxes = all.filter(e => e.type === 'checkbox' && (e.required || e.getAttribute('aria-required') === 'true'));
  requiredBoxes.filter(e => !e.checked)
    .forEach(e => { e.checked = true; e.dispatchEvent(new Event('change', { bubbles: true })); out.consents.ticked++; });
  out.consents.required = requiredBoxes.length;
  return '${MARKER}' + JSON.stringify(out);
}
""".trimIndent()
    }
}
