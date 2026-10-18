package dev.mstaszew.campaign.worker

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "worker")
data class WorkerProperties(
    /** shadow: consume + re-dedup + release (no browser apply). live: full apply. */
    var applyMode: String = "shadow",
    /**
     * Attempts before a message is dead-lettered. Bounded by job_state.attempts,
     * not by the broker, so the count survives a worker restart.
     */
    var maxAttempts: Int = 3,
    /** One message at a time matches the campaign's one-tab discipline. */
    var workerId: String = "worker-1",
    /** msrouter OpenAI-compatible base URL. */
    var msrouterUrl: String = "http://127.0.0.1:8787/v1",
    var msrouterApiKey: String? = null,
    var msrouterModel: String = "mst/free",
    /**
     * CDP endpoint of the shared campaign Chrome. Used only by the CV upload
     * fallback (DOM.setFileInputFiles) when the portal never opens a native
     * file chooser; same host the Playwright MCP is pointed at.
     */
    var cdpUrl: String = "http://127.0.0.1:9222",
)