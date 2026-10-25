package dev.mstaszew.campaign.finder

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WindowSummaryTest {

    @Test
    fun `counts each outcome kind`() {
        val summary = WindowSummary()
        summary.record(FinderPipeline.Outcome.Enqueued(75, "stack fit"))
        summary.record(FinderPipeline.Outcome.Duplicate("window-scan", "a:1"))
        summary.record(FinderPipeline.Outcome.Duplicate("window-scan", "a:2"))
        summary.record(FinderPipeline.Outcome.Duplicate("COMPANY", "ref-1"))
        summary.record(FinderPipeline.Outcome.Duplicate("ID", "ref-2"))
        summary.record(FinderPipeline.Outcome.Duplicate("URL", "ref-3"))
        summary.record(FinderPipeline.Outcome.Ineligible("STACK", "finder:STACK: php"))
        summary.record(FinderPipeline.Outcome.LowScore(40, "off-topic"))

        assertThat(summary.seen).isEqualTo(8)
        assertThat(summary.enqueued).isEqualTo(1)
        assertThat(summary.dupWindow).isEqualTo(2)
        assertThat(summary.deduped).isEqualTo(3)
        assertThat(summary.ineligible).isEqualTo(1)
        assertThat(summary.lowScore).isEqualTo(1)
    }

    @Test
    fun `line renders every counter`() {
        val summary = WindowSummary()
        summary.record(FinderPipeline.Outcome.Enqueued(70, "stack fit"))
        summary.record(FinderPipeline.Outcome.Duplicate("window-scan", "a:1"))

        assertThat(summary.line())
            .isEqualTo(
                "window summary: seen=2 dupWindow=1 deduped=0 ineligible=0 lowScore=0 enqueued=1 sendFailed=0 failed=0",
            )
    }

    @Test
    fun `send failures count toward seen and their own counter`() {
        val summary = WindowSummary()
        summary.record(FinderPipeline.Outcome.SendFailed("broker down"))
        summary.record(FinderPipeline.Outcome.Enqueued(70, "stack fit"))

        assertThat(summary.seen).isEqualTo(2)
        assertThat(summary.sendFailed).isEqualTo(1)
        assertThat(summary.enqueued).isEqualTo(1)
    }

    @Test
    fun `failures count toward seen`() {
        val summary = WindowSummary()
        summary.recordFailure()
        summary.record(FinderPipeline.Outcome.Enqueued(70, "stack fit"))

        assertThat(summary.seen).isEqualTo(2)
        assertThat(summary.failed).isEqualTo(1)
    }
}
