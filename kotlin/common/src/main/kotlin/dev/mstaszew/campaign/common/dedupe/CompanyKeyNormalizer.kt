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

    /**
     * Keys that hide the real employer; never treated as a company match.
     * Besides the legacy confidential markers this carries the board UI chip
     * vocabulary: card scrapers historically picked badge lines ("new",
     * "viewed", "B2B", "Backend") as the company, and the first application
     * recorded under a chip key company-matched every other chip-keyed
     * listing, silently emptying the funnel (2026-10-21 outage: 545 listings
     * blocked by one "new"-keyed application). "remote" is deliberately NOT a
     * chip: Remote.com is a real employer. Generic words like "data", "mobile"
     * and "qa" carry an accepted collision risk: a real employer would have to
     * normalize to exactly that word for a false sentinel, and the failure
     * mode is only a skipped company-dedupe for that one employer.
     */
    val CHIP_KEYS = setOf(
        "new", "nowa", "nowe", "viewed", "start-asap", "quick-apply",
        "easy-apply", "polecamy",
        "b2b", "uop", "uod",
        "backend", "frontend", "fullstack", "devops", "testing", "qa",
        "mobile", "data", "ai-ml", "ml", "architecture", "hybrid",
        "sprawdź-wynagrodzenie", "sprawdzisz-zarobki",
    )

    val SENTINEL_KEYS = setOf("confidential", "anonymous", "חברה-חסויה") + CHIP_KEYS

    fun isSentinelKey(companyKey: String): Boolean = companyKey in SENTINEL_KEYS
}
