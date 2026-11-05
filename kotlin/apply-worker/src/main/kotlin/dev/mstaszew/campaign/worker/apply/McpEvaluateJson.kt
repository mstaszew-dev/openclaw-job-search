package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Extracts a marker-prefixed JSON report from a playwright-mcp
 * browser_evaluate result. The REAL MCP shape (captured 2026-11-04 22:51)
 * returns the string value JSON-ENCODED ("...\"filled\":true..."), so a naive
 * {...} slice dies on the backslash escapes ("Unexpected character ('\')") -
 * the enclosing string must be decoded first. The code echo after
 * "### Ran Playwright code" repeats the marker (with braces), so every
 * occurrence is tried. Shared by apply_click (APPLY_PROBE_MARKER) and
 * profile_fill (PF_REPORT); each tool's JS prefixes its marker to the VALUE,
 * where it always survives the MCP text round-trip.
 */
internal object McpEvaluateJson {

    fun extract(mapper: ObjectMapper, text: String, marker: String): JsonNode? {
        var idx = text.indexOf(marker)
        while (idx >= 0) {
            // Shape 1: a bare JSON object right after the marker (try the
            // nearest closing brace first, then the last one in the text).
            val start = text.indexOf('{', idx)
            if (start >= 0) {
                for (end in intArrayOf(text.indexOf('}', start), text.lastIndexOf('}'))) {
                    if (end > start) {
                        try {
                            return mapper.readTree(text.substring(start, end + 1))
                        } catch (_: Exception) {
                            // fall through to shape 2
                        }
                    }
                }
            }
            // Shape 2 (the real MCP shape): the value is a JSON-encoded
            // string - decode the enclosing "..." then parse the inner {...}.
            val openQuote = text.lastIndexOf('"', idx)
            if (openQuote >= 0) {
                var i = openQuote + 1
                while (i < text.length) {
                    when (text[i]) {
                        '\\' -> i += 2
                        '"' -> break
                        else -> i++
                    }
                }
                if (i < text.length) {
                    try {
                        val decoded = mapper.readValue(text.substring(openQuote, i + 1), String::class.java)
                        val ds = decoded.indexOf('{')
                        val de = decoded.lastIndexOf('}')
                        if (ds >= 0 && de > ds) {
                            return mapper.readTree(decoded.substring(ds, de + 1))
                        }
                    } catch (_: Exception) {
                        // fall through to the next marker occurrence
                    }
                }
            }
            idx = text.indexOf(marker, idx + 1)
        }
        return null
    }
}
