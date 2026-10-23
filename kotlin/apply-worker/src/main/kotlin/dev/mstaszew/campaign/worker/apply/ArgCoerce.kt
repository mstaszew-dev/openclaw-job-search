package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import java.text.Normalizer

/**
 * Deterministic rescue layer between the model's text-JSON tool call and
 * playwright-mcp's strict schemas. Free-tier backends (measured live
 * 2026-10-22: poolside 6/6, flint 3/6) emit `browser_fill_form` fields as an
 * object map {label: value} and drop `type` or use synonyms; playwright-mcp
 * rejects every one of those shapes and the loop breaker then kills the
 * attempt after 3 identical failures. Rather than burning steps teaching the
 * model the schema, normalize the arguments before dispatch:
 * - map -> array of {target, name, type, value}, type inferred from the last
 *   snapshot's element kind (combobox/checkbox/...), textbox as fallback;
 * - array items: missing `type` -> inferred/textbox, synonyms
 *   (text/select/dropdown/...) -> schema enum values; non-string values
 *   stringified (playwright-mcp 0.0.83 pins `value: z.string()`);
 * - single field object -> wrapped into a one-element array;
 * - `browser_evaluate` script/code/expression/js aliases -> `function`.
 * Schema-valid input passes through untouched, so stronger models are not
 * affected.
 *
 * Semantics preserved on purpose: uncoercible input (scalar `fields`, missing
 * `fields` key) passes through so playwright-mcp still fails LOUDLY - an empty
 * array would fabricate a successful no-op on a real employer's form. Array
 * items that are not objects are skipped with a logged warning for the same
 * reason.
 *
 * Type inference uses [BrowserAgent] `LoopState.lastSnapshot`, which only
 * updates on explicit browser_snapshot calls (playwright-mcp's compact
 * post-action page blocks are not captured), so on a miss the type falls back
 * to "textbox" - required either way because the pinned schema marks `type`
 * as a required enum.
 *
 * Output contract: never mutates its input; returned nodes may share `value`
 * subtrees with the input, and both must be treated as read-only (the only
 * consumer serializes them).
 */
internal object ArgCoerce {

    private val log = LoggerFactory.getLogger(ArgCoerce::class.java)

    /** playwright-mcp's fields[i].type enum (0.0.83, required). */
    private val VALID_TYPES = setOf("textbox", "checkbox", "radio", "combobox", "slider")

    /** Common model synonyms for the enum values. */
    private val TYPE_SYNONYMS = mapOf(
        "text" to "textbox",
        "string" to "textbox",
        "input" to "textbox",
        "textarea" to "textbox",
        "email" to "textbox",
        "tel" to "textbox",
        "phone" to "textbox",
        "number" to "textbox",
        "date" to "textbox",
        "select" to "combobox",
        "dropdown" to "combobox",
        "list" to "combobox",
        "choice" to "radio",
        "boolean" to "checkbox",
        "toggle" to "checkbox",
    )

    /** `[f6f7] combobox "Forma zatrudnienia"` -> label-to-kind lookup. */
    private val SNAPSHOT_ELEMENT = Regex(
        """\[\S+]\s+(textbox|combobox|checkbox|radio|slider)\s+"([^"]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** `() => x`, `async x => x`, `function f(){}` - already a function. */
    private val FUNCTION_EXPRESSION = Regex("""^\s*(async\s+)?(\(|function\b|[A-Za-z_$][\w$]*\s*=>)""")

    /** `const x = ...`, `return x`, `for (...)`, `await fetch(...)` - statement bodies. */
    private val STATEMENT_START = Regex("""^\s*(const|let|var|for|while|do|if|switch|try|class|return|await)\b""")

    /** Only these tool names are ever reshaped; ledger tools pass raw. */
    private val COERCIBLE_TOOLS = setOf("browser_fill_form", "browser_evaluate", "browser_navigate")

    private val mapper = ObjectMapper()

    fun normalize(toolName: String, args: JsonNode, lastSnapshot: String): JsonNode {
        // Ledger tools keep raw args BY DESIGN (dispatch reads them directly;
        // a fabricated ledger arg is worse than a loud failure) - gate here,
        // at the function level, not just at the call site.
        if (toolName !in COERCIBLE_TOOLS) return args
        val unwrapped = unwrap(args)
        val obj = when {
            unwrapped is ObjectNode -> unwrapped
            // a bare string URL for navigate (models emit "https://..." as the
            // whole arguments value) is the one scalar shape worth salvaging
            toolName == "browser_navigate" && unwrapped.isTextual ->
                mapper.createObjectNode().put("url", unwrapped.asText())
            else -> return unwrapped
        }
        return when (toolName) {
            "browser_fill_form" -> normalizeFillForm(obj, lastSnapshot)
            "browser_evaluate" -> normalizeEvaluate(obj)
            "browser_navigate" -> normalizeNavigate(obj)
            else -> obj
        }
    }

    /**
     * Undo the two envelope shapes behind the 2026-10-23 "expected string,
     * received undefined -> at url/function" step burn: the model re-wraps the
     * whole call (`{name, arguments:{...}}`) or double-encodes the arguments
     * JSON string. Browser tools only - the ledger tools keep raw args by
     * design (dispatch reads them directly; a fabricated ledger arg is worse
     * than a loud failure).
     */
    private fun unwrap(args: JsonNode): JsonNode {
        var cur = args
        if (cur.isTextual) {
            val parsed = try { mapper.readTree(cur.asText()) } catch (_: Exception) { null }
            if (parsed != null && parsed.isObject) cur = parsed
        }
        if (cur.isObject && cur.has("name") && cur.get("name").isTextual &&
            cur.has("arguments") && cur.get("arguments").isObject
        ) {
            return cur.get("arguments")
        }
        return cur
    }

    private fun normalizeNavigate(args: ObjectNode): JsonNode {
        val url = args.get("url")
        if (url != null && url.isTextual) return args
        val alias = listOf("href", "link", "location")
            .firstOrNull { args.has(it) && args.get(it).isTextual } ?: return args
        val out = mapper.createObjectNode()
        out.put("url", args.get(alias).asText())
        args.fieldNames().asSequence()
            .filter { it != alias && it != "url" }
            .forEach { out.set<JsonNode>(it, args.get(it)) }
        return out
    }

    private fun normalizeFillForm(args: ObjectNode, lastSnapshot: String): JsonNode {
        val raw = args.get("fields") ?: return args
        val kinds = snapshotKinds(lastSnapshot)
        val out = mapper.createObjectNode()
        out.set<JsonNode>(
            "fields",
            when {
                raw is ArrayNode -> coerceArray(raw, kinds)
                // a single {name/target, ..., value} object is ONE field, not
                // a label:value map (the keys would collide with field schema
                // keys); a name/target without value still means one field
                raw.isObject && (raw.has("name") || raw.has("target")) ->
                    coerceArray(mapper.createArrayNode().add(raw), kinds)
                raw.isObject -> coerceMap((raw as ObjectNode).fields(), kinds)
                else -> return args
            },
        )
        // preserve any sibling properties (none known today, but harmless)
        args.fieldNames().asSequence()
            .filter { it != "fields" }
            .forEach { out.set<JsonNode>(it, args.get(it)) }
        return out
    }

    private fun coerceArray(items: ArrayNode, kinds: Map<String, String>): ArrayNode {
        val out = mapper.createArrayNode()
        for (item in items) {
            if (item !is ObjectNode) {
                log.warn("ArgCoerce: dropped non-object fill_form field ({})", item.nodeType)
                continue
            }
            val name = item.path("name").asText(item.path("target").asText(""))
            val coerced = mapper.createObjectNode()
            coerced.put("target", item.path("target").asText(name))
            coerced.put("name", name)
            coerced.put("type", resolveType(item.path("type").asText(null), name, kinds))
            coerced.put("value", item.path("value").asText(""))
            out.add(coerced)
        }
        return out
    }

    private fun coerceMap(fields: Iterator<Map.Entry<String, JsonNode>>, kinds: Map<String, String>): ArrayNode {
        val out = mapper.createArrayNode()
        for ((name, value) in fields) {
            val entry = mapper.createObjectNode()
            entry.put("target", name)
            entry.put("name", name)
            entry.put("type", resolveType(null, name, kinds))
            // schema pins value to string; models emit numbers/booleans
            entry.put("value", value.asText(""))
            out.add(entry)
        }
        return out
    }

    private fun normalizeEvaluate(args: ObjectNode): JsonNode {
        val fn = args.get("function")
        if (fn != null && fn.isTextual) {
            val wrapped = wrapStatementBody(fn.asText()) ?: return args
            val out = mapper.createObjectNode()
            out.put("function", wrapped)
            args.fieldNames().asSequence()
                .filter { it != "function" }
                .forEach { out.set<JsonNode>(it, args.get(it)) }
            return out
        }
        val alias = listOf("script", "code", "expression", "js")
            .firstOrNull { args.has(it) && args.get(it).isTextual } ?: return args
        val out = mapper.createObjectNode()
        val body = args.get(alias).asText()
        out.put("function", wrapStatementBody(body) ?: body)
        args.fieldNames().asSequence()
            .filter { it != alias && it != "function" }
            .forEach { out.set<JsonNode>(it, args.get(it)) }
        return out
    }

    /**
     * playwright-mcp 0.0.83 evaluates `function` as `eval("(" + expr + ")")`:
     * statement bodies ("const x = ...; return x") throw `SyntaxError:
     * Unexpected token 'const'` (seen live 2026-10-23), and so do top-level
     * awaits. Keyword- or await-led bodies are wrapped in an async IIFE -
     * await-safe, and a `return` inside still delivers the value. Everything
     * else passes through untouched so failures stay loud: a semicolon inside
     * a string literal (`labels.join(';')`) is a VALID expression, and
     * wrapping it would silently downgrade the result to undefined (an
     * evaluate success then resets the loop-breaker streak - the exact
     * cap-out pathology this layer feeds).
     */
    private fun wrapStatementBody(body: String): String? {
        val t = body.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("(") || t.startsWith("function")) return null
        if (FUNCTION_EXPRESSION.containsMatchIn(t)) return null
        if (STATEMENT_START.containsMatchIn(t)) {
            log.warn("ArgCoerce: evaluate body looked like statements; wrapped as async IIFE")
            return "(async () => { $t })()"
        }
        return null
    }

    private fun resolveType(rawType: String?, name: String, kinds: Map<String, String>): String {
        if (rawType != null) {
            val lower = rawType.lowercase()
            if (lower in VALID_TYPES) return lower
            if (lower in TYPE_SYNONYMS) return TYPE_SYNONYMS.getValue(lower)
        }
        val key = fold(name)
        kinds[key]?.let { return it }
        kinds.entries.firstOrNull { (label, _) -> label in key || key in label }
            ?.let { return it.value }
        return "textbox"
    }

    private fun snapshotKinds(snapshot: String): Map<String, String> =
        SNAPSHOT_ELEMENT.findAll(snapshot)
            .associate { fold(it.groupValues[2]) to it.groupValues[1].lowercase() }

    /** Lowercase, trim, and strip diacritics so "Imie" matches "Imię". */
    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
