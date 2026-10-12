package dev.mstaszew.campaign.common.dedupe

/**
 * Exact port of the campaign companyKey algorithm (DEDUPE.md Step 1, matching
 * the retired normalize.py, whose legal-form regex allows arbitrary whitespace
 * between letters so `spzoo`, `srl`, `spa`, `nv`, `bv`, `as`, `sc`, `sro` also
 * strip). Changing any step silently breaks dedup against the imported tracker
 * history - golden tests pin the behavior.
 */
object CompanyKeyNormalizer {

    private val LEGAL_FORMS = listOf(
        "sp z o o", "sp zoo", "s a", "sa", "ltd", "limited", "llc", "inc", "gmbh", "ag",
        "n v", "b v", "oy", "ab", "a s", "s r l", "s p a", "s c", "s r o", "kft", "co",
    )

    /** One pattern per legal form: letters with optional whitespace between, preceded by a space. */
    private val LEGAL_STRIP_PATTERNS: List<java.util.regex.Pattern> = LEGAL_FORMS.map { form ->
        val letters = form.replace(" ", "").map { "$it\\s*" }.joinToString("")
        java.util.regex.Pattern.compile(
            "^(.*?)\\s${letters}\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE or
                java.util.regex.Pattern.DOTALL or
                java.util.regex.Pattern.UNICODE_CHARACTER_CLASS,
        )
    }

    private val WHITESPACE = java.util.regex.Pattern.compile(
        "\\s+",
        java.util.regex.Pattern.UNICODE_CHARACTER_CLASS,
    )

    fun normalize(company: String?): String {
        if (company.isNullOrBlank()) return ""
        // 1. lowercase + trim
        var s = company.lowercase().trim()
        // 2. '&' -> ' and '
        s = s.replace("&", " and ")
        // 3. '.', ',', '_', '/' -> ' '
        s = s.replace('.', ' ').replace(',', ' ').replace('_', ' ').replace('/', ' ')
        // 4. collapse whitespace
        s = s.split(WHITESPACE).filter { it.isNotBlank() }.joinToString(" ")
        // 5. strip up to two trailing legal forms
        repeat(2) {
            val stripped = stripTrailingLegalForm(s) ?: return@repeat
            s = stripped
        }
        // 6. keep only letters, digits, whitespace, hyphen (Unicode-aware)
        s = s.filter { it.isLetterOrDigit() || it.isWhitespace() || it == '-' }
        // 7. whitespace -> single hyphens, strip edge hyphens
        return s.split(WHITESPACE).filter { it.isNotBlank() }.joinToString("-").trim('-')
    }

    /** Returns the string minus one trailing legal form, or null if none matches. */
    private fun stripTrailingLegalForm(s: String): String? {
        for (pattern in LEGAL_STRIP_PATTERNS) {
            val matcher = pattern.matcher(s)
            if (!matcher.matches()) continue
            val stripped = matcher.group(1).trim()
            if (stripped.isNotEmpty()) return stripped
        }
        return null
    }

    /** Keys that hide the real employer; never treated as a company match. */
    val SENTINEL_KEYS = setOf("confidential", "anonymous", "חברה-חסויה")

    fun isSentinelKey(companyKey: String): Boolean = companyKey in SENTINEL_KEYS
}
