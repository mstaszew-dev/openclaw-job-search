package dev.mstaszew.campaign.common.dedupe

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Golden cases from the campaign dedupe spec (DEDUPE.md Step 1); the Kotlin
 * port must match normalize.py byte for byte or imports will not dedupe.
 */
class CompanyKeyNormalizerTest {

    @Test
    fun `spec examples`() {
        val cases = mapOf(
            "Google LLC" to "google",
            "Cellebrite Mobile Synchronization Ltd" to "cellebrite-mobile-synchronization",
            "LivePerson Inc." to "liveperson",
            "Funds-Tech Sp. z o.o." to "funds-tech",
            "Dector Sp. z o.o." to "dector",
        )
        cases.forEach { (input, expected) ->
            assertThat(CompanyKeyNormalizer.normalize(input))
                .describedAs("company %s", input)
                .isEqualTo(expected)
        }
    }

    @Test
    fun `board chip keys are sentinels`() {
        // 2026-10-21: theprotocol UI badges (new, viewed, start-asap,
        // quick-apply) and nofluffjobs category/contract chips (backend,
        // fullstack, b2b, uop, ...) were scraped as company names; the first
        // application per chip key then company-matched every chip-keyed
        // listing and silently emptied the funnel. Chip keys hide the real
        // employer, so they must behave like "confidential".
        val chips = listOf(
            "new", "nowa", "nowe", "viewed", "start-asap", "quick-apply",
            "easy-apply", "polecamy",
            "b2b", "uop", "uod",
            "backend", "frontend", "fullstack", "devops", "testing", "qa",
            "mobile", "data", "ai-ml", "ml", "architecture", "hybrid",
            "sprawdź-wynagrodzenie", "sprawdzisz-zarobki",
        )
        chips.forEach { key ->
            assertThat(CompanyKeyNormalizer.isSentinelKey(key))
                .describedAs("chip key %s", key)
                .isTrue()
        }
        // real employers keep working company dedupe
        assertThat(CompanyKeyNormalizer.isSentinelKey("ryb-tech")).isFalse()
        assertThat(CompanyKeyNormalizer.isSentinelKey("link-group")).isFalse()
        // "remote" is deliberately NOT a chip: Remote.com is a real employer
        assertThat(CompanyKeyNormalizer.isSentinelKey("remote")).isFalse()
    }

    @Test
    fun `scraped chip text normalizes into sentinel keys`() {
        // DedupService sees normalize(scrapedText), so the sentinel behavior
        // must survive the normalization round trip; a normalize() change that
        // remaps a chip (e.g. "Start ASAP" to "startasap") must fail here.
        val scraped = mapOf(
            "New" to "new",
            "B2B" to "b2b",
            "UoP" to "uop",
            "Start ASAP" to "start-asap",
            "Quick Apply" to "quick-apply",
            "AI/ML" to "ai-ml",
            "Sprawdź wynagrodzenie" to "sprawdź-wynagrodzenie",
        )
        scraped.forEach { (text, key) ->
            assertThat(CompanyKeyNormalizer.normalize(text))
                .describedAs("normalize %s", text)
                .isEqualTo(key)
            assertThat(CompanyKeyNormalizer.isSentinelKey(CompanyKeyNormalizer.normalize(text)))
                .describedAs("sentinel via normalize(%s)", text)
                .isTrue()
        }
    }

    @Test
    fun `strips at most two trailing legal forms`() {
        assertThat(CompanyKeyNormalizer.normalize("Bar Ltd LLC")).isEqualTo("bar")
        assertThat(CompanyKeyNormalizer.normalize("Baz GmbH AG SA")).isEqualTo("baz-gmbh")
    }

    @Test
    fun `collapsed legal form variants strip like normalize py`() {
        assertThat(CompanyKeyNormalizer.normalize("ACME srl")).isEqualTo("acme")
        assertThat(CompanyKeyNormalizer.normalize("Funds-Tech spzoo")).isEqualTo("funds-tech")
        assertThat(CompanyKeyNormalizer.normalize("Nova spa")).isEqualTo("nova")
        assertThat(CompanyKeyNormalizer.normalize("Delta bv")).isEqualTo("delta")
        assertThat(CompanyKeyNormalizer.normalize("Orion sro")).isEqualTo("orion")
        assertThat(CompanyKeyNormalizer.normalize("Upsilon s p a")).isEqualTo("upsilon")
    }

    @Test
    fun `ampersand becomes and`() {
        assertThat(CompanyKeyNormalizer.normalize("Smith & Sons Ltd")).isEqualTo("smith-and-sons")
    }

    @Test
    fun `dots slashes underscores become spaces then hyphens`() {
        assertThat(CompanyKeyNormalizer.normalize("Foo.Bar/Baz_Qux n.v.")).isEqualTo("foo-bar-baz-qux")
    }

    @Test
    fun `unicode letters are kept`() {
        assertThat(CompanyKeyNormalizer.normalize("Żółć Ltd")).isEqualTo("żółć")
        assertThat(CompanyKeyNormalizer.normalize("אר.ויי.בי. פלסמנט אגנסי (RWB)"))
            .isEqualTo("אר-ויי-בי-פלסמנט-אגנסי-rwb")
    }

    @Test
    fun `collapses whitespace to single hyphens and strips edges`() {
        assertThat(CompanyKeyNormalizer.normalize("  Foo   Bar  ")).isEqualTo("foo-bar")
    }

    @Test
    fun `bare legal suffix alone is not stripped`() {
        assertThat(CompanyKeyNormalizer.normalize("LTD")).isEqualTo("ltd")
    }

    @Test
    fun `case insensitive suffix with trailing dot`() {
        assertThat(CompanyKeyNormalizer.normalize("Acme inc.")).isEqualTo("acme")
        assertThat(CompanyKeyNormalizer.normalize("Acme s.p.a.")).isEqualTo("acme")
    }
}
