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
