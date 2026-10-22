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

    @Test
    fun `ledger tool names are outside the coercion surface`() {
        for (tool in listOf("record_submission", "skip_task", "upload_cv")) {
            val args = mapper.createObjectNode().apply { put("fields", "garbage") }
            val before = args.deepCopy()

            assertThat(ArgCoerce.normalize(tool, args, "snapshot")).isEqualTo(before)
        }
    }
}
