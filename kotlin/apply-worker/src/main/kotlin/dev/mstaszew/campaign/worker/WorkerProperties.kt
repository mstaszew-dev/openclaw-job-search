package dev.mstaszew.campaign.worker

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "worker")
data class WorkerProperties(
    /** shadow: claim + re-dedup + release (no browser apply). live: full apply. */
    var applyMode: String = "shadow",
    var leaseSeconds: Int = 600,
    var pollIntervalSeconds: Int = 30,
    var workerId: String = "worker-1",
    /** One task at a time matches the campaign's one-tab discipline. */
    var reaperIntervalSeconds: Int = 60,
    /** msrouter OpenAI-compatible base URL. */
    var msrouterUrl: String = "http://127.0.0.1:8787/v1",
    var msrouterApiKey: String? = null,
    var msrouterModel: String = "mst/free",
)
