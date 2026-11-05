package dev.mstaszew.campaign.common.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The step timeout must cover the SLOWEST provider in the gateway chain, not
 * the fastest. 2026-11-05: with openrouter 429-parked, the chain falls to the
 * laptop (local qwen3.5-2B over tailscale), which legitimately needs minutes
 * per apply-size prompt; the old 90s step timeout aborted those requests,
 * triple-burned LlmClient's retries and failed the attempt
 * ("chat failed after 3 attempts", the inzynier death) while the gateway
 * itself allows 30 minutes per provider (LAPTOP_TIMEOUT_MS).
 */
class AgentLoopBoundsTest {

    @Test
    fun `default step timeout covers a slow local model`() {
        // The compile-time default, NOT the env-derived val: the default is
        // what matters, and the val is env-coupled by design (NICE-1).
        assertThat(AgentLoopBounds.DEFAULT_STEP_TIMEOUT_MS).isEqualTo(300_000L)
    }

    @Test
    fun `step timeout override parses, clamps and falls back like max steps`() {
        assertThat(AgentLoopBounds.parseStepTimeoutMs("600000")).isEqualTo(600_000L)
        // Below 30s: clamped (a step must at least cover a gateway round-trip).
        assertThat(AgentLoopBounds.parseStepTimeoutMs("100")).isEqualTo(30_000L)
        // Garbage/blank/null: the default.
        assertThat(AgentLoopBounds.parseStepTimeoutMs(null)).isEqualTo(300_000L)
        assertThat(AgentLoopBounds.parseStepTimeoutMs("  ")).isEqualTo(300_000L)
        assertThat(AgentLoopBounds.parseStepTimeoutMs("abc")).isEqualTo(300_000L)
        // Above 30 min: clamped (the gateway's own laptop ceiling).
        assertThat(AgentLoopBounds.parseStepTimeoutMs("999999999")).isEqualTo(1_800_000L)
    }

    /**
     * The step ceiling must keep worstCase x 1.1 + 30min margin inside Kafka's
     * Type.INT poll interval even at the clamp (review SHOULD-2: the old
     * blind 10,000 constant overflowed 2x with the 300s step).
     */
    @Test
    fun `max steps ceiling keeps the poll interval inside Kafka Type-INT at the clamp`() {
        val worstAtCeiling = AgentLoopBounds.MAX_STEPS_CEILING.toLong() * AgentLoopBounds.stepWorstCaseMs
        val budget = worstAtCeiling + maxOf(
            java.time.Duration.ofMinutes(30).toMillis(),
            worstAtCeiling / 10,
        )
        assertThat(budget).isLessThan(Int.MAX_VALUE.toLong())
        // And the ceiling is not so low it clips ordinary operation.
        assertThat(AgentLoopBounds.MAX_STEPS_CEILING).isGreaterThanOrEqualTo(AgentLoopBounds.DEFAULT_MAX_STEPS)
    }

    @Test
    fun `worst case math keeps the poll interval inside Kafka Type-INT`() {
        // The REAL production formula (KafkaWiring), not a local re-derivation:
        // max.poll.interval.ms = worstCase + max(30min, worstCase/10).
        assertThat(KafkaWiring.MAX_POLL_INTERVAL_MS).isLessThan(Int.MAX_VALUE.toLong())
        assertThat(KafkaWiring.MAX_POLL_INTERVAL_MS).isGreaterThan(0)
    }
}
