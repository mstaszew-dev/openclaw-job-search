package dev.mstaszew.campaign.common.messaging

/**
 * The agent loop's timing bounds, stated once. KafkaWiring (this module) and
 * BrowserAgent (apply-worker) both read from here, so WORKER_MAX_STEPS raises
 * the loop and its poll budget together and the old duplicated-literal drift
 * - where raising one side silently left the other below its budget - cannot
 * happen again. PollBudgetTest pins the relationship from the apply-worker
 * side, the only module that sees both.
 */
object AgentLoopBounds {
    /** 60 x (90s model + 120s Playwright) = 3.5h bounded worst case. */
    const val DEFAULT_MAX_STEPS = 60

    /**
     * Upper clamp chosen so worstCaseMs + margin still fits Kafka's Type.INT
     * poll-interval config (10,000 x 210s = 2.1e9 ms < Int.MAX_VALUE).
     */
    const val MAX_STEPS_CEILING = 10_000

    /** Effective step cap: WORKER_MAX_STEPS override, else the default. */
    val effectiveMaxSteps: Int = parseMaxSteps(System.getenv("WORKER_MAX_STEPS"))

    const val STEP_TIMEOUT_MS = 90_000L
    const val TOOL_TIMEOUT_MS = 120_000L

    /** null/blank/garbage fall back to the default; clamped to [1, 10000].
     *  A numeric string beyond Int range parses as null, so it also takes
     *  the default rather than the ceiling - safe either way. */
    fun parseMaxSteps(raw: String?): Int =
        raw?.trim()?.toIntOrNull()?.coerceIn(1, MAX_STEPS_CEILING) ?: DEFAULT_MAX_STEPS

    /** What one apply can take at most, without margin. */
    val worstCaseMs: Long = effectiveMaxSteps.toLong() * (STEP_TIMEOUT_MS + TOOL_TIMEOUT_MS)
}
