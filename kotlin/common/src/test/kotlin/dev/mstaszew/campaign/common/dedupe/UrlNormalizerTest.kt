package dev.mstaszew.campaign.common.dedupe

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Golden cases from DEDUPE.md Step 2. */
class UrlNormalizerTest {

    @Test
    fun `lowercases scheme host and path and strips trailing slash`() {
        assertThat(UrlNormalizer.normalize("https://Example.com/Path/To/Job/"))
            .isEqualTo("https://example.com/path/to/job")
    }

    @Test
    fun `keeps only jobid param with lowercased value`() {
        assertThat(UrlNormalizer.normalize("https://x.com/j?foo=1&jobid=AbC&bar=2"))
            .isEqualTo("https://x.com/j?jobid=abc")
    }

    @Test
    fun `matches jobid param case-insensitively`() {
        assertThat(UrlNormalizer.normalize("https://x.com/j?JobID=XYZ"))
            .isEqualTo("https://x.com/j?jobid=xyz")
    }

    @Test
    fun `drops query when no jobid param`() {
        assertThat(UrlNormalizer.normalize("https://x.com/j?other=1&v=2"))
            .isEqualTo("https://x.com/j")
    }

    @Test
    fun `drops fragment`() {
        assertThat(UrlNormalizer.normalize("https://x.com/j#apply"))
            .isEqualTo("https://x.com/j")
    }

    @Test
    fun `drops default ports`() {
        assertThat(UrlNormalizer.normalize("https://x.com:443/j")).isEqualTo("https://x.com/j")
        assertThat(UrlNormalizer.normalize("http://x.com:80/j")).isEqualTo("http://x.com/j")
        assertThat(UrlNormalizer.normalize("https://x.com:8443/j")).isEqualTo("https://x.com:8443/j")
    }

    @Test
    fun `blank input returns empty`() {
        assertThat(UrlNormalizer.normalize("")).isEmpty()
        assertThat(UrlNormalizer.normalize(null)).isEmpty()
    }
}
