package dev.mstaszew.campaign.finder.collect

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * One discovery run drives two different transports: the browser boards go
 * through the Playwright MCP collector, and the RSS board is a plain HTTP
 * GET. The pipeline holds one collector port, so the routing lives here and
 * stays a pure lookup: a source with no route falls through to the browser
 * collector, which rejects unknown boards loudly.
 */
class SourceRouterCollectorTest {

    private fun listing(source: String, id: String) = CollectedListing(
        source = source,
        sourceJobId = id,
        company = "Acme",
        roleTitle = "Senior Java Developer",
        url = "https://example.com/$id",
        remotePolicy = "remote",
    )

    @Test
    fun `a routed source goes to its own collector`() {
        val rss = ListingCollector { source -> listOf(listing(source, "rss")) }
        val browser = ListingCollector { source -> listOf(listing(source, "browser")) }

        val router = SourceRouterCollector(mapOf("solidjobs" to rss), browser)

        assertThat(router.collect("solidjobs").single().sourceJobId).isEqualTo("rss")
        assertThat(router.collect("justjoin").single().sourceJobId).isEqualTo("browser")
    }

    @Test
    fun `every source in the window has a collector`() {
        val browser = ListingCollector { source -> listOf(listing(source, "browser")) }
        val router = SourceRouterCollector(mapOf("solidjobs" to browser), browser)

        assertThat(router.routesForTest()).containsExactly("solidjobs")
    }
}
