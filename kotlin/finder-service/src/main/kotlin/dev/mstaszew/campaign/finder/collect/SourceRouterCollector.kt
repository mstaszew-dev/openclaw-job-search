package dev.mstaszew.campaign.finder.collect

/**
 * Dispatches one discovery run's sources to their transports: the browser
 * boards go through the Playwright MCP collector, and the RSS board is a
 * plain HTTP GET. The pipeline keeps its single collector port, so the
 * routing stays a lookup here; a source without a route falls through to
 * the browser collector, which rejects unknown boards loudly instead of
 * silently dropping the source.
 */
class SourceRouterCollector(
    private val routes: Map<String, ListingCollector>,
    private val fallback: ListingCollector,
) : ListingCollector {

    override fun collect(source: String): List<CollectedListing> =
        (routes[source] ?: fallback).collect(source)

    /** Visible for the wiring test. */
    internal fun routesForTest(): Set<String> = routes.keys
}
