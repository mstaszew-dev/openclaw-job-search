package dev.mstaszew.campaign.finder.collect

import dev.mstaszew.campaign.finder.policy.EligibilityProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * solid.jobs publishes its whole catalogue as one RSS feed (~3,700 items,
 * 1.7 MB, verified 2026-11-13). It needs no browser and no selectors, but
 * only a remote slice of it matches the campaign (~65 items), so the
 * collector filters before the pipeline sees anything: without that, the
 * whole catalogue would drown the funnel counters and the dupe lookups for
 * ~65 useful rows every window.
 */
class RssListingCollectorTest {

    private fun feed(vararg items: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0"><channel>
        <title>SOLID.Jobs - Oferty pracy</title>
        <link>https://solid.jobs/</link>
        ${items.joinToString("\n")}
        </channel></rss>
    """.trimIndent()

    private fun item(
        guid: String = "e34d4314-3cf2-4d35-a16f-1c97e5f324b1",
        link: String = "https://solid.jobs/o/s2mtuxg1/rss",
        title: String = "Fullstack Java Developer (Remote)",
        description: String = "apreel • Centrum, Warszawa\n20.2k–26.9k PLN netto/m (B2B)",
        categories: List<String> = listOf("IT", "Java"),
    ): String = """
        <item>
          <guid isPermaLink="false">$guid</guid>
          <link>$link</link>
          ${categories.joinToString("\n") { "<category>$it</category>" }}
          <title>$title</title>
          <description>$description</description>
          <pubDate>Wed, 12 Aug 2026 10:56:08 +0200</pubDate>
        </item>
    """.trimIndent()

    private fun collect(xml: String): List<CollectedListing> =
        RssListingCollector(
            stackAllowlist = EligibilityProperties().stackAllowlist,
            fetch = { xml },
        ).collect(RssListingCollector.SOURCE)

    @Test
    fun `remote offer in a target stack becomes a listing`() {
        val listings = collect(feed(item()))

        assertThat(listings).hasSize(1)
        val l = listings.single()
        assertThat(l.source).isEqualTo("solidjobs")
        assertThat(l.sourceJobId).isEqualTo("s2mtuxg1")
        assertThat(l.url).isEqualTo("https://solid.jobs/o/s2mtuxg1")
        assertThat(l.company).isEqualTo("apreel")
        assertThat(l.roleTitle).isEqualTo("Fullstack Java Developer (Remote)")
        assertThat(l.remotePolicy).isEqualTo("remote")
        assertThat(l.stack).containsExactly("it", "java")
        assertThat(l.salaryMin).isEqualByComparingTo("20200")
        assertThat(l.salaryMax).isEqualByComparingTo("26900")
    }

    @Test
    fun `php and react offers are kept as well as java`() {
        val listings = collect(
            feed(
                item(guid = "g1", link = "https://solid.jobs/o/php001/rss", title = "PHP Developer (Remote)", categories = listOf("IT", "PHP")),
                item(guid = "g2", link = "https://solid.jobs/o/react1/rss", title = "Senior Frontend Developer (React + React Native) (Remote)", categories = listOf("IT", "React")),
            ),
        )

        assertThat(listings.map { it.sourceJobId }).containsExactly("php001", "react1")
    }

    @Test
    fun `onsite offer is dropped even in a target stack`() {
        val listings = collect(
            feed(item(guid = "g1", link = "https://solid.jobs/o/onsite/rss", title = "Java Developer", categories = listOf("IT", "Java"))),
        )

        assertThat(listings).isEmpty()
    }

    @Test
    fun `remote offer outside the target stacks is dropped`() {
        val listings = collect(
            feed(item(guid = "g1", link = "https://solid.jobs/o/sales/rss", title = "Regional Sales Manager (Remote)", categories = listOf("IT", "Sales"))),
        )

        assertThat(listings).isEmpty()
    }

    @Test
    fun `item without an offer link fails closed`() {
        val listings = collect(feed(item(link = "https://solid.jobs/")))

        assertThat(listings).isEmpty()
    }

    @Test
    fun `plain salary ranges parse without the k suffix`() {
        val listings = collect(
            feed(item(description = "Scalo • Aleja Pokoju 18, Kraków\n18 000–22 500 PLN netto/m (B2B)")),
        )

        assertThat(listings.single().salaryMin).isEqualByComparingTo("18000")
        assertThat(listings.single().salaryMax).isEqualByComparingTo("22500")
    }

    @Test
    fun `a missing salary line leaves the bounds empty`() {
        val listings = collect(feed(item(description = "Scalo • Kraków")))

        assertThat(listings.single().salaryMin).isNull()
        assertThat(listings.single().salaryMax).isNull()
        assertThat(listings.single().company).isEqualTo("Scalo")
    }

    /**
     * A single amount used to parse as "no salary", so the pipeline's 15k
     * B2B floor never saw it and a live "0 PLN brutto/m (Umowa o staż)"
     * intern offer would have been enqueued. Both bounds now carry the
     * amount, so the floor decides.
     */
    @Test
    fun `a single amount fills both bounds`() {
        val listings = collect(feed(item(description = "apreel • Kraków\n0 PLN brutto/m (Umowa o staż)")))

        assertThat(listings.single().salaryMin).isEqualByComparingTo("0")
        assertThat(listings.single().salaryMax).isEqualByComparingTo("0")
    }

    @Test
    fun `a non breaking space thousands separator parses`() {
        val listings = collect(
            feed(item(description = "Scalo • Kraków\n18\u00a0000\u201322\u00a0500 PLN netto/m (B2B)")),
        )

        assertThat(listings.single().salaryMin).isEqualByComparingTo("18000")
        assertThat(listings.single().salaryMax).isEqualByComparingTo("22500")
    }

    @Test
    fun `the short link is stable across feed refreshes`() {
        // The guid changes per publication; the /o/<short>/rss path is the
        // identity the board redirects to the canonical offer page, so it is
        // the id the dedupe and the apply worker both see.
        val first = collect(feed(item(guid = "aaa")))
        val second = collect(feed(item(guid = "bbb")))

        assertThat(first.single().sourceJobId).isEqualTo(second.single().sourceJobId)
        assertThat(first.single().url).isEqualTo(second.single().url)
    }
}