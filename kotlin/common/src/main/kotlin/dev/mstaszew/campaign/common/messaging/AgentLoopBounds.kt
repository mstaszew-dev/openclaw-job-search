package dev.mstaszew.campaign.common.messaging

import java.time.Duration

/**
 * The agent loop's timing bounds, stated once. KafkaWiring (this module) and
 * BrowserAgent (apply-worker) both read from here, so WORKER_MAX_STEPS raises
 * the loop and its poll budget together and the old duplicated-literal drift
 * - where raising one side silently left the other below its budget - cannot
 * happen again. PollBudgetTest pins the relationship from the apply-worker
 * side, the only module that sees both.
 */
object AgentLoopBounds {

    /** 80 x ~17.25min/step = 23h bounded worst case (see stepWorstCaseMs). */
    const val DEFAULT_MAX_STEPS = 80

    const val TOOL_TIMEOUT_MS = 120_000L

    /**
     * Per-LLM-step timeout. Raised 90s -> 300s on 2026-11-05: when the
     * gateway's cloud providers are 429-parked, the chain falls to the
     * LAPTOP (local model over tailscale), which legitimately needs minutes
     * per apply-size prompt. 90s aborted those requests while the gateway
     * itself allows 30 min per provider (LAPTOP_TIMEOUT_MS), triple-burned
     * LlmClient's retries and failed the attempt ("chat failed after 3
     * attempts", the inzynier death). Override with WORKER_STEP_TIMEOUT_MS;
     * clamped to [30s, 30min] - the ceiling mirrors the gateway's own
     * laptop ceiling.
     */
    val STEP_TIMEOUT_MS: Long = parseStepTimeoutMs(System.getenv("WORKER_STEP_TIMEOUT_MS"))

    const val DEFAULT_STEP_TIMEOUT_MS = 300_000L
    const val MIN_STEP_TIMEOUT_MS = 30_000L
    const val MAX_STEP_TIMEOUT_MS = 1_800_000L

    /**
     * Chat retry shape, MIRRORED from LlmClient's defaults (attempts=3,
     * backoff 5s x attempt) - evolve together; the review of 2026-11-05 found
     * the poll budget undercounted by exactly this multiplier (3x90s + 15s of
     * backoffs per step vs the budgeted single 90s).
     */
    const val CHAT_ATTEMPTS = 3
    const val CHAT_RETRY_BACKOFF_TOTAL_MS = 15_000L

    /**
     * One step at its TRUE worst: every chat attempt times out (review
     * SHOULD-1: LlmClient retries inside one step), then Playwright runs.
     */
    val stepWorstCaseMs: Long =
        CHAT_ATTEMPTS * STEP_TIMEOUT_MS + CHAT_RETRY_BACKOFF_TOTAL_MS + TOOL_TIMEOUT_MS

    /**
     * Upper clamp DERIVED from Kafka's Type.INT poll-interval bound: the
     * ceiling must keep worstCase x 1.1 + 30min margin inside Int.MAX_VALUE
     * (the headroom test pins the 1.1 factor). With the default 300s step
     * this evaluates to 1884 steps - far above any operational cap, but no
     * longer a blind constant that silently overflowed (review SHOULD-2).
     */
    val MAX_STEPS_CEILING: Int =
        (((Int.MAX_VALUE - Duration.ofMinutes(30).toMillis()) * 10L) /
            (11L * stepWorstCaseMs)).toInt()

    /** Effective step cap: WORKER_MAX_STEPS override, else the default. */
    val effectiveMaxSteps: Int = parseMaxSteps(System.getenv("WORKER_MAX_STEPS"))

    /** null/blank/garbage fall back to the default; clamped to [1, ceiling]. */
    fun parseMaxSteps(raw: String?): Int =
        raw?.trim()?.toIntOrNull()?.coerceIn(1, MAX_STEPS_CEILING) ?: DEFAULT_MAX_STEPS

    /** Same contract as parseMaxSteps, bounded to a sane step-timeout range. */
    fun parseStepTimeoutMs(raw: String?): Long =
        raw?.trim()?.toLongOrNull()?.coerceIn(MIN_STEP_TIMEOUT_MS, MAX_STEP_TIMEOUT_MS)
            ?: DEFAULT_STEP_TIMEOUT_MS

    /** What one apply can take at most, without margin (retry-aware). */
    val worstCaseMs: Long = effectiveMaxSteps.toLong() * stepWorstCaseMs
}
