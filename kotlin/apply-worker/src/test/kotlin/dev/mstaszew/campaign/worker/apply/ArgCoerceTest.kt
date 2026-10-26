package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Free-tier models emit fill_form/evaluate arguments in shapes the
 * playwright-mcp schemas reject (object map instead of the fields array,
 * missing `function` key). The coercer is the deterministic rescue layer:
 * schema-shaped input passes through untouched, known wrong shapes are
 * normalized, and uncoercible input passes through so playwright-mcp still
 * fails loudly (an empty fields array would fabricate a successful no-op on a
 * real employer's form). Fidelity observed live 2026-10-22 (poolside + flint
 * both emit the map shape 6/6), so the map case is the primary contract here.
 */
class ArgCoerceTest {

    private val mapper = ObjectMapper()

    private fun mapFields(vararg pairs: Pair<String, Any>) =
        mapper.createObjectNode().apply {
            pairs.forEach { (k, v) ->
                when (v) {
                    is Boolean -> put(k, v)
                    is Int, is Long -> put(k, (v as Number).toLong())
                    else -> put(k, v.toString())
                }
            }
        }

    private fun snapshotWithCombobox() = """
        [f1a2] heading "Aplikuj - Senior Java Developer"
        [f2b3] textbox "Imie i nazwisko" [required]
        [f6f7] combobox "Forma zatrudnienia" [required]
        [f7a8] button "Wyslij aplikacje"
    """.trimIndent()

    @Test
    fun `object map fields become array items with snapshot-inferred types`() {
        val args = mapper.createObjectNode().apply {
            set<JsonNode>("fields", mapFields("Imie i nazwisko" to "Michael", "Forma zatrudnienia" to "B2B"))
        }

        val out = ArgCoerce.normalize("browser_fill_form", args, snapshotWithCombobox())

        val fields = out.path("fields")
        assertThat(fields.isArray).isTrue
        assertThat(fields).hasSize(2)
        assertThat(fields[0].path("name").asText()).isEqualTo("Imie i nazwisko")
        assertThat(fields[0].path("target").asText()).isEqualTo("Imie i nazwisko")
        assertThat(fields[0].path("type").asText()).isEqualTo("textbox")
        assertThat(fields[0].path("value").asText()).isEqualTo("Michael")
        assertThat(fields[1].path("type").asText()).isEqualTo("combobox")
    }

    @Test
    fun `label matching is case and diacritics insensitive`() {
        val args = mapper.createObjectNode().apply {
            set<JsonNode>("fields", mapFields("FORMA ZATRUDNIENIA" to "B2B"))
        }

        val out = ArgCoerce.normalize("browser_fill_form", args, snapshotWithCombobox().replace("Wyslij", "Wyślij"))

        assertThat(out.path("fields")[0].path("type").asText()).isEqualTo("combobox")
    }

    @Test
    fun `object map values are stringified for the schema`() {
        val args = mapper.createObjectNode().apply {
            set<JsonNode>(
                "fields",
                mapper.createObjectNode().apply {
                    put("Wynagrodzenie", 15000)
                    put("Newsletter", true)
                },
            )
        }

        val out = ArgCoerce.normalize("browser_fill_form", args, "")

        val fields = out.path("fields")
        assertThat(fields[0].path("value").asText()).isEqualTo("15000")
        assertThat(fields[1].path("value").asText()).isEqualTo("true")
    }

    @Test
    fun `object map without snapshot defaults every field to textbox`() {
        val args = mapper.createObjectNode().apply {
            set<JsonNode>("fields", mapFields("Email" to "a@b.c"))
        }

        val out = ArgCoerce.normalize("browser_fill_form", args, "")

        val fields = out.path("fields")
        assertThat(fields[0].path("type").asText()).isEqualTo("textbox")
    }

    @Test
    fun `array items with missing or synonymous types are normalized`() {
        val items = mapper.createArrayNode().apply {
            add(mapper.createObjectNode().apply {
                put("name", "Email"); put("value", "a@b.c")
            })
            add(mapper.createObjectNode().apply {
                put("name", "Forma"); put("type", "select"); put("value", "B2B")
            })
            add(mapper.createObjectNode().apply {
                put("name", "Newsletter"); put("type", "checkbox"); put("value", "true")
            })
        }
        val args = mapper.createObjectNode().apply { set<JsonNode>("fields", items) }

        val out = ArgCoerce.normalize("browser_fill_form", args, "")

        val fields = out.path("fields")
        assertThat(fields[0].path("type").asText()).isEqualTo("textbox")
        assertThat(fields[1].path("type").asText()).isEqualTo("combobox")
        assertThat(fields[2].path("type").asText()).isEqualTo("checkbox")
    }

    @Test
    fun `valid schema-shaped array passes through unchanged`() {
        val items = mapper.createArrayNode().apply {
            add(mapper.createObjectNode().apply {
                put("target", "f2b3"); put("name", "Imie i nazwisko")
                put("type", "textbox"); put("value", "Michael")
            })
        }
        val args = mapper.createObjectNode().apply { set<JsonNode>("fields", items) }
        val before = args.deepCopy()

        val out = ArgCoerce.normalize("browser_fill_form", args, snapshotWithCombobox())

        assertThat(out).isEqualTo(before)
    }

    @Test
    fun `single field object is wrapped into the fields array`() {
        val field = mapper.createObjectNode().apply {
            put("name", "Email"); put("type", "text"); put("value", "a@b.c")
        }
        val args = mapper.createObjectNode().apply { set<JsonNode>("fields", field) }

        val out = ArgCoerce.normalize("browser_fill_form", args, "")

        val fields = out.path("fields")
        assertThat(fields.isArray).isTrue
        assertThat(fields).hasSize(1)
        assertThat(fields[0].path("type").asText()).isEqualTo("textbox")
    }

    @Test
    fun `single field object without value still wraps with an empty value`() {
        val field = mapper.createObjectNode().apply { put("name", "Email") }
        val args = mapper.createObjectNode().apply { set<JsonNode>("fields", field) }

        val out = ArgCoerce.normalize("browser_fill_form", args, "")

        val fields = out.path("fields")
        assertThat(fields).hasSize(1)
        assertThat(fields[0].path("name").asText()).isEqualTo("Email")
        assertThat(fields[0].path("value").asText()).isEmpty()
    }

    @Test
    fun `non-object array items are dropped and object items survive`() {
        val items = mapper.createArrayNode().apply {
            add("Email=Michael")
            add(mapper.createObjectNode().apply { put("name", "Email"); put("value", "a@b.c") })
        }
        val args = mapper.createObjectNode().apply { set<JsonNode>("fields", items) }

        val out = ArgCoerce.normalize("browser_fill_form", args, "")

        val fields = out.path("fields")
        assertThat(fields).hasSize(1)
        assertThat(fields[0].path("name").asText()).isEqualTo("Email")
    }

    @Test
    fun `scalar fields value passes through so the tool fails loudly`() {
        val args = mapper.createObjectNode().apply { put("fields", "email=a@b.c") }
        val before = args.deepCopy()

        assertThat(ArgCoerce.normalize("browser_fill_form", args, "")).isEqualTo(before)
    }

    @Test
    fun `missing fields key passes through`() {
        val args = mapper.createObjectNode().apply { put("element", "f2b3") }
        val before = args.deepCopy()

        assertThat(ArgCoerce.normalize("browser_fill_form", args, "")).isEqualTo(before)
    }

    @Test
    fun `evaluate script aliases map to the function parameter`() {
        for (alias in listOf("script", "code", "expression", "js")) {
            val args = mapper.createObjectNode().apply { put(alias, "() => 1") }

            val out = ArgCoerce.normalize("browser_evaluate", args, "")

            assertThat(out.path("function").asText()).isEqualTo("() => 1")
        }
    }

    @Test
    fun `evaluate with function parameter passes through`() {
        val args = mapper.createObjectNode().apply { put("function", "() => 2") }
        val before = args.deepCopy()

        assertThat(ArgCoerce.normalize("browser_evaluate", args, "")).isEqualTo(before)
    }

    @Test
    fun `other tools are returned untouched`() {
        val args = mapper.createObjectNode().apply { put("url", "https://example.com") }
        val before = args.deepCopy()

        assertThat(ArgCoerce.normalize("browser_navigate", args, "")).isEqualTo(before)
    }

    // ---- 2026-10-23: shapes observed burning steps in 80-step cap-outs ----

    @Test
    fun `envelope wrapped arguments are unwrapped for browser tools`() {
        val args = mapper.createObjectNode().apply {
            put("name", "browser_navigate")
            set<JsonNode>("arguments", mapper.createObjectNode().apply { put("url", "https://x/1") })
        }

        val out = ArgCoerce.normalize("browser_navigate", args, "")

        assertThat(out.path("url").asText()).isEqualTo("https://x/1")
    }

    @Test
    fun `envelope wrapping is unwrapped before fill form coercion`() {
        val args = mapper.createObjectNode().apply {
            put("name", "browser_fill_form")
            set<JsonNode>(
                "arguments",
                mapper.createObjectNode().apply { set<JsonNode>("fields", mapFields("Email" to "m@example.com")) },
            )
        }

        val out = ArgCoerce.normalize("browser_fill_form", args, snapshotWithCombobox())

        val fields = out.path("fields")
        assertThat(fields.isArray).isTrue
        assertThat(fields[0].path("value").asText()).isEqualTo("m@example.com")
    }

    @Test
    fun `double encoded json arguments are parsed once`() {
        val args = mapper.readTree(""""{\"url\":\"https://x/2\"}"""")

        val out = ArgCoerce.normalize("browser_navigate", args, "")

        assertThat(out.path("url").asText()).isEqualTo("https://x/2")
    }

    @Test
    fun `navigate url is recovered from href style aliases`() {
        val args = mapper.createObjectNode().apply { put("href", "https://x/3") }

        val out = ArgCoerce.normalize("browser_navigate", args, "")

        assertThat(out.path("url").asText()).isEqualTo("https://x/3")
    }

    @Test
    fun `navigate bare string arguments become the url`() {
        val out = ArgCoerce.normalize("browser_navigate", mapper.readTree("\"https://x/4\""), "")

        assertThat(out.path("url").asText()).isEqualTo("https://x/4")
    }

    @Test
    fun `evaluate statement bodies are wrapped as an async iife`() {
        val args = mapper.createObjectNode().apply {
            put("function", "const x = document.querySelector('h1'); return x.textContent")
        }

        val out = ArgCoerce.normalize("browser_evaluate", args, "")
        val fn = out.path("function").asText()

        assertThat(fn).startsWith("(async () => {")
        assertThat(fn).contains("const x = document.querySelector('h1')")
        assertThat(fn).endsWith("})()")
    }

    @Test
    fun `evaluate expressions and arrow functions pass through unwrapped`() {
        for (body in listOf("document.title", "() => document.title", "x => x.value", "() => { return 1 }")) {
            val args = mapper.createObjectNode().apply { put("function", body) }
            val before = args.deepCopy()

            assertThat(ArgCoerce.normalize("browser_evaluate", args, "")).isEqualTo(before)
        }
    }

    /** A `;` inside a string literal is a VALID expression - eval("(" + t + ")")
     *  accepts it; wrapping it would silently return undefined. */
    @Test
    fun `evaluate expression with a semicolon inside a string literal passes through`() {
        val body = "Array.from(document.querySelectorAll('option')).map(e => e.textContent).join(';')"
        val args = mapper.createObjectNode().apply { put("function", body) }
        val before = args.deepCopy()

        assertThat(ArgCoerce.normalize("browser_evaluate", args, "")).isEqualTo(before)
    }

    /** Top-level await is a SyntaxError under eval("(" + t + ")") - wrap it. */
    @Test
    fun `evaluate await led bodies are wrapped as an async iife`() {
        val args = mapper.createObjectNode().apply {
            put("function", "await fetch('/api/state').then(r => r.text())")
        }

        val out = ArgCoerce.normalize("browser_evaluate", args, "")
        val fn = out.path("function").asText()

        assertThat(fn).startsWith("(async () => {")
        assertThat(fn).contains("await fetch('/api/state')")
        assertThat(fn).endsWith("})()")
    }

    @Test
    fun `ledger tool names are outside the coercion surface`() {
        for (tool in listOf("record_submission", "skip_task", "upload_cv")) {
            val args = mapper.createObjectNode().apply { put("fields", "garbage") }
            val before = args.deepCopy()

            assertThat(ArgCoerce.normalize(tool, args, "snapshot")).isEqualTo(before)
        }
    }

    /**
     * Tab switching is now part of the apply contract (justjoin's Apply opens
     * the external ATS form in a new tab), and free-tier models will emit the
     * index as a string and the action with wrong case; playwright-mcp's zod
     * schema would burn a step on each and three burn the attempt.
     */
    @Test
    fun `browser_tabs arguments are coerced (action lowercase, string index to number, alias)`() {
        val out = ArgCoerce.normalize(
            "browser_tabs",
            mapper.readTree("""{"action": "Select", "tab": "1"}"""),
            "",
        )

        assertThat(out.path("action").asText()).isEqualTo("select")
        assertThat(out.path("index").asInt()).isEqualTo(1)
        assertThat(out.path("index").isNumber).isTrue()
    }

    @Test
    fun `browser_tabs schema-valid input passes through untouched`() {
        val args = mapper.readTree("""{"action": "list"}""")

        assertThat(ArgCoerce.normalize("browser_tabs", args, "")).isEqualTo(args)
    }
}
