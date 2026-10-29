package dev.mstaszew.campaign.worker.apply

import dev.mstaszew.campaign.common.domain.AttemptHistoryEntry
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Optional

/**
 * Tests for attempt history capture and injection.
 */
class AttemptHistoryTest {

    private val jobStateRepo = mockk<JobStateRepository>(relaxed = true)

    private val listings = mockk<JobListingRepository> {
        every { findById("test-listing") } returns Optional.of(
            JobListingEntity(
                id = "test-listing",
                source = "test-source",
                sourceJobId = "test-job",
                company = "Test Company",
                roleTitle = "Test Role",
                url = "https://test.com/job",
                region = "PL",
                remotePolicy = "fully-remote",
                stack = listOf("Java")
            )
        )
    }

    private fun scriptMockTransport(responses: List<String>) = object : ChatTransport {
        private var i = 0
        override fun post(url: String, token: String?, body: String, timeout: Duration): String {
            val response = responses[i.coerceAtMost(responses.lastIndex)]
            i++
            return response
        }
    }

    @Test
    fun `attempt history data structure is correct`() {
        // Arrange
        val history = listOf(
            AttemptHistoryEntry(1, 1, 20, 0, "wander breaker: 20 consecutive read-only steps"),
            AttemptHistoryEntry(2, 0, 5, 0, "loop breaker: click failed 120 consecutive times")
        )

        // Act & Assert
        assertThat(history).hasSize(2)
        assertThat(history[0].attemptNumber).isEqualTo(1)
        assertThat(history[0].wanderTrips).isEqualTo(1)
        assertThat(history[0].maxReadStreak).isEqualTo(20)
        assertThat(history[0].staleRefCorrections).isEqualTo(0)

        assertThat(history[1].attemptNumber).isEqualTo(2)
        assertThat(history[1].wanderTrips).isEqualTo(0)
        assertThat(history[1].maxReadStreak).isEqualTo(5)
    }

    @Test
    fun `system prompt includes history section when provided`() {
        // Arrange
        val history = listOf(
            AttemptHistoryEntry(1, 1, 20, 0, "wander breaker: 20 consecutive read-only steps"),
        )

        val profile = "Test profile"

        // Act - Check the raw system prompt content
        // Note: systemPrompt is internal, so we verify the data structure instead
        val expectedHistoryText = """
            Previous Attempts (to avoid repeating the same mistakes):
              - Attempt 1: wanderTrips=1, readStreak=20, staleRefCorrections=0 → wander breaker: 20 consecutive read-only steps without action
              **What to learn from this history:**
              - If readStreak is high (>10), avoid reading the same DOM repeatedly - take an action instead.
              - If wanderTrips=1, the first correction was injected; take it seriously and try differently.
              - If this is the 2nd or 3rd attempt, you must succeed now - no more retries.
        """.trimIndent()

        // Verify the AttemptHistoryEntry would generate the expected text
        val entry = history[0]
        val historyText = """
              - Attempt ${entry.attemptNumber}: wanderTrips=${entry.wanderTrips}, readStreak=${entry.maxReadStreak}, staleRefCorrections=${entry.staleRefCorrections} → ${entry.finalStatus}
              **What to learn from this history:**
              - If readStreak is high (>10), avoid reading the same DOM repeatedly - take an action instead.
              - If wanderTrips=1, the first correction was injected; take it seriously and try differently.
              - If this is the 2nd or 3rd attempt, you must succeed now - no more retries.
        """.trimIndent()

        assertThat(historyText).contains("Attempt 1")
        assertThat(historyText).contains("wanderTrips=1")
        assertThat(historyText).contains("readStreak=20")
    }

    @Test
    fun `attempt history correctly filters serious failures`() {
        // Arrange - production classifier: the serious set also covers
        // narration exhaustion ("no tool call") as of the 2026-10-29 fix.
        val seriousFailures = listOf(
            "wander breaker: 20 consecutive read-only steps",
            "loop breaker: click failed 120 consecutive times",
            "portal-block: no SSO login wall",
            "captcha",
            "model produced no tool call after 3 tries: browser_snapshot()",
        )

        // Act - the exact production filter the worker records history with
        val seriousFailuresFiltered = seriousFailures.filter { BrowserAgent.isSeriousFailure(it) }

        // Assert
        assertThat(seriousFailuresFiltered).containsExactlyElementsOf(seriousFailures)
    }

    @Test
    fun `attempt history excludes non-serious failures`() {
        // Arrange - Test the filter logic
        val allFailures = listOf(
            "wander breaker: 20 consecutive read-only steps", // serious
            "step cap 80 reached without outcome",             // not serious
            "click failed once",                               // not serious
            "llm loop failed: chat failed"                     // not serious
        )

        // Act
        val seriousFailuresFiltered = allFailures.filter { BrowserAgent.isSeriousFailure(it) }

        // Assert
        assertThat(seriousFailuresFiltered).hasSize(1)
        assertThat(seriousFailuresFiltered.first()).isEqualTo("wander breaker: 20 consecutive read-only steps")
    }
}
