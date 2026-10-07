package dev.mstaszew.campaign.finder.collect

import java.math.BigDecimal

/** A listing harvested from a board page (pre-normalization). */
data class CollectedListing(
    val source: String,
    val sourceJobId: String,
    val company: String,
    val roleTitle: String,
    val url: String,
    val region: String = "PL",
    val remotePolicy: String? = null,
    val salaryMin: BigDecimal? = null,
    val salaryMax: BigDecimal? = null,
    val stack: List<String> = emptyList(),
    val rawJson: String? = null,
)

/** Port used by the pipeline; the MCP-backed implementation drives the board pages. */
fun interface ListingCollector {
    fun collect(source: String): List<CollectedListing>
}
