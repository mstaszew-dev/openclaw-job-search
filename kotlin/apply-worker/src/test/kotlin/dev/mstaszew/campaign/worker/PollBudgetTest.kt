package dev.mstaszew.campaign.worker

import dev.mstaszew.campaign.common.messaging.KafkaWiring
import dev.mstaszew.campaign.worker.apply.BrowserAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the consumer's poll budget against the agent loop it has to cover.
 *
 * This lives in `apply-worker`, not in `common`, because that is the only module
 * that can see both sides. The version of this check that sat in `common`
 * compared `MAX_POLL_INTERVAL_MS` against copies of the agent constants living
 * in `KafkaWiring` itself, so raising `BrowserAgent.MAX_STEPS` passed it while
 * leaving the consumer below its budget. That is exactly the drift the number
 * exists to prevent.
 *
 * Pure arithmetic, so it runs without Docker.
 */
class PollBudgetTest {

    private val worstCaseMs: Long =
        BrowserAgent.MAX_STEPS * (BrowserAgent.STEP_TIMEOUT.toMillis() + BrowserAgent.TOOL_TIMEOUT.toMillis())

    @Test
    fun `poll interval covers the real worst case apply loop`() {
        assertThat(KafkaWiring.MAX_POLL_INTERVAL_MS.toLong()).isGreaterThan(worstCaseMs)
    }

    @Test
    fun `the mirrored constants in KafkaWiring still match the agent`() {
        assertThat(KafkaWiring.AGENT_MAX_STEPS).isEqualTo(BrowserAgent.MAX_STEPS)
        assertThat(KafkaWiring.AGENT_STEP_TIMEOUT_MS).isEqualTo(BrowserAgent.STEP_TIMEOUT.toMillis())
        assertThat(KafkaWiring.AGENT_TOOL_TIMEOUT_MS).isEqualTo(BrowserAgent.TOOL_TIMEOUT.toMillis())
    }
}