package dev.mstaszew.campaign.finder

/**
 * Per-window outcome accumulator for the discovery pass. Most funnel exits
 * are silent (dedupe duplicates and low scores leave no trace anywhere; same-
 * window re-sightings each log a line nobody aggregates), so a collapsed
 * window used to be invisible: on 2026-10-21 every theprotocol listing died
 * at the dedupe company branch for 17 hours before anyone noticed. One
 * summary line per run turns that into a one-glance diagnosis.
 */
class WindowSummary {

    var seen: Int = 0; private set
    var dupWindow: Int = 0; private set
    var deduped: Int = 0; private set
    var ineligible: Int = 0; private set
    var lowScore: Int = 0; private set
    var enqueued: Int = 0; private set
    var failed: Int = 0; private set

    fun record(outcome: FinderPipeline.Outcome) {
        seen++
        when (outcome) {
            is FinderPipeline.Outcome.Enqueued -> enqueued++
            is FinderPipeline.Outcome.Duplicate ->
                if (outcome.matchedOn == WINDOW_SCAN_TAG) dupWindow++ else deduped++
            is FinderPipeline.Outcome.Ineligible -> ineligible++
            is FinderPipeline.Outcome.LowScore -> lowScore++
        }
    }

    fun recordFailure() {
        seen++
        failed++
    }

    fun line(): String =
        "window summary: seen=$seen dupWindow=$dupWindow deduped=$deduped " +
            "ineligible=$ineligible lowScore=$lowScore enqueued=$enqueued failed=$failed"

    companion object {
        /** matchedOn tag FinderPipeline uses for same-window re-sightings. */
        const val WINDOW_SCAN_TAG = "window-scan"
    }
}
