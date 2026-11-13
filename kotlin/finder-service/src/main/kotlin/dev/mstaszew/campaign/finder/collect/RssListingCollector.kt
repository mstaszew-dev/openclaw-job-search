package dev.mstaszew.campaign.finder.collect

import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Harvests solid.jobs from its public RSS feed: one HTTP GET returns the
 * board's whole catalogue (3,684 items, 1.7 MB, verified 2026-11-13), so
 * this source needs no browser, no selectors and no scroll handling, and it
 * cannot wedge the shared Chrome.
 *
 * The feed is the full catalogue, not a recent-items window, and only a
 * remote slice of it matches the campaign (~65 of ~3,700 on 2026-11-13:
 * Java 41, PHP 10, Node 7, React 5, JavaScript 2). The collector filters
 * before the pipeline sees anything; otherwise every window would push the
 * whole catalogue through the funnel counters and the dedupe lookups for
 * ~65 useful rows.
 *
 * Identity is the short id in the item link (/o/<short>/rss), not the guid:
 * the short link is what the board redirects to the canonical offer page,
 * so the apply worker and the dedupe both see the same URL every window.
 */
class RssListingCollector(
    private val stackAllowlist: Set<String>,
    private val feedUrl: String = DEFAULT_FEED_URL,
    private val fetch: (String) -> String = { httpGet(it) },
) : ListingCollector {

    override fun collect(source: String): List<CollectedListing> {
        val xml = fetch(feedUrl)
        val items = ITEM.findAll(xml).map { it.groupValues[1] }.toList()
        val kept = items.mapNotNull(::toListing)
        log.info(
            "solid.jobs feed: {} items, {} remote in a target stack",
            items.size,
            kept.size,
        )
        return kept
    }

    private fun toListing(item: String): CollectedListing? {
        val title = tag("title", item)?.let(::unescape) ?: return null
        if (!isRemote(title)) return null

        val categories = CATEGORY.findAll(item)
            .map { unescape(it.groupValues[1]).lowercase() }
            .toList()
        if (categories.none { category -> stackAllowlist.any { category.contains(it) } }) return null

        val link = tag("link", item) ?: return null
        val shortId = SHORT_ID.find(link)?.groupValues?.get(1) ?: return null

        val description = tag("description", item)?.let(::unescape).orEmpty()
        val (salaryMin, salaryMax) = salaryBounds(description)

        return CollectedListing(
            source = SOURCE,
            sourceJobId = shortId,
            company = company(description),
            roleTitle = title,
            url = "$OFFER_BASE/o/$shortId",
            remotePolicy = "remote",
            salaryMin = salaryMin,
            salaryMax = salaryMax,
            stack = categories,
        )
    }

    /** The board marks remote offers in the title; the rest are onsite. */
    private fun isRemote(title: String): Boolean =
        title.contains("remote", ignoreCase = true) || title.contains("zdaln", ignoreCase = true)

    /**
     * The description is two lines: "company • city" then an optional salary
     * ("20.2k–26.9k PLN netto/m (B2B)"). A description without the separator
     * yields the whole first line as the company; that only widens the
     * company dedupe, since id and url matching still apply.
     */
    private fun company(description: String): String =
        description.lineSequence().firstOrNull()
            ?.split('•')?.firstOrNull()?.trim().orEmpty()

    /**
     * "20.2k–26.9k PLN" is a range, "18 000 PLN" is a single amount. A
     * single amount fills BOTH bounds so the 15k B2B floor still applies: a
     * live "0 PLN brutto/m (Umowa o staż)" intern offer parsed as "no
     * salary" and skipped the floor entirely.
     */
    private fun salaryBounds(description: String): Pair<BigDecimal?, BigDecimal?> {
        SALARY_RANGE.find(description)?.let {
            return amount(it.groupValues[1], it.groupValues[2]) to
                amount(it.groupValues[3], it.groupValues[4])
        }
        SALARY_SINGLE.find(description)?.let {
            val value = amount(it.groupValues[1], it.groupValues[2])
            return value to value
        }
        return null to null
    }

    private fun amount(number: String, kSuffix: String): BigDecimal {
        // Thousands arrive as "18 000" (space, sometimes NBSP); BigDecimal
        // only accepts a bare digit string, and Java's \s does not match
        // U+00A0, so strip both explicitly.
        val normalized = number
            .filterNot { it.isWhitespace() || it == '\u00a0' }
            .replace(',', '.')
        val value = BigDecimal(normalized)
        return if (kSuffix.isNotEmpty()) value.multiply(BigDecimal(1000)) else value
    }

    private fun tag(name: String, block: String): String? =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)?.trim()

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    companion object {
        const val SOURCE = "solidjobs"
        const val DEFAULT_FEED_URL = "https://solid.jobs/rss/job-offers"
        private const val OFFER_BASE = "https://solid.jobs"

        private val log = LoggerFactory.getLogger(RssListingCollector::class.java)

        private val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        private val CATEGORY = Regex("<category>(.*?)</category>", RegexOption.DOT_MATCHES_ALL)
        private val SHORT_ID = Regex("/o/([^/?#]+)")
        private const val NUM = "(\\d[\\d\\s\\u00a0]*(?:[.,]\\d+)?)"
        private val SALARY_RANGE = Regex(
            "$NUM\\s*(k?)\\s*[\u2013-]\\s*$NUM\\s*(k?)\\s*PLN",
            RegexOption.IGNORE_CASE,
        )
        private val SALARY_SINGLE = Regex("$NUM\\s*(k?)\\s*PLN", RegexOption.IGNORE_CASE)

        private fun httpGet(url: String): String {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Mozilla/5.0 (compatible; campaign-finder/1.0)")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) {
                "solid.jobs feed returned HTTP ${response.statusCode()}"
            }
            return response.body()
        }
    }
}
