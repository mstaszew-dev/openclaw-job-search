package dev.mstaszew.campaign.finder

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "finder")
data class FinderProperties(
    /** msrouter OpenAI-compatible base URL, e.g. http://127.0.0.1:8787/v1 */
    var msrouterUrl: String = "http://127.0.0.1:8787/v1",
    var msrouterApiKey: String? = null,
    var msrouterModel: String = "mst/free",
    /**
     * Operator kill switch for gateway CV scoring. When false the finder still
     * collects and produces, but never calls the gateway, so a paused msrouter
     * costs no tokens.
     */
    var scoringEnabled: Boolean = true,
    /**
     * Browser-lease hold time for one discovery pass. The watchdog renews
     * while the scan runs, so this bounds only crash recovery: how long a
     * dead finder wedges the browser. Kept well above the longest scan.
     */
    var leaseTtlMinutes: Long = 30,
    /** Watchdog renewal cadence while a discovery pass is still running. */
    var leaseRenewSeconds: Long = 300,
    /** Chrome CDP endpoint exposed by the lubuntu-agent pod (socat relay). */
    var browserCdpUrl: String = "http://127.0.0.1:9222",
    /** Full command that starts the Playwright MCP stdio server. */
    var mcpCommand: String = "node @playwright/mcp/cli.js --cdp-endpoint http://127.0.0.1:9222",
    var collectStacks: List<String> = listOf("java", "kotlin", "php", "node", "react"),
    /**
     * Operator kill switch for the periodic re-grind pass. When false the
     * finder never re-enqueues rows that earlier attempts left FAILED.
     */
    var regrindEnabled: Boolean = true,
    /**
     * Re-grind batch cap per pass. One worker consumes the topic sequentially,
     * so a small batch keeps it fed without flooding the queue with retries.
     */
    var regrindBatchPerWindow: Int = 4,
    /**
     * A row re-FAILED more recently than this is skipped, so one pass cannot
     * grind the same jobs hour after hour before their next attempt has had
     * a chance to run.
     */
    var regrindCooldownHours: Long = 12,
    /**
     * Campaign-wide attempt ceiling for re-grind selection. Matches the
     * worker's default maxAttempts; per-row maxAttempts from the stored
     * document is still what the claim guard enforces.
     */
    var regrindMaxAttempts: Int = 3,
    /** Re-grind pass cadence; kept aligned with the discovery run interval. */
    var regrindIntervalSeconds: Long = 900,
)
