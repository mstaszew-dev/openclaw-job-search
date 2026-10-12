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
    /** Chrome CDP endpoint exposed by the lubuntu-agent pod (socat relay). */
    var browserCdpUrl: String = "http://127.0.0.1:9222",
    /** Full command that starts the Playwright MCP stdio server. */
    var mcpCommand: String = "node @playwright/mcp/cli.js --cdp-endpoint http://127.0.0.1:9222",
    var collectStacks: List<String> = listOf("java", "kotlin", "php", "node", "react"),
)
