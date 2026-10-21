package dev.mstaszew.campaign.worker.apply

import dev.mstaszew.campaign.common.net.SlackClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Posts campaign outcomes to #jobcampaign: wins, new portal blocks, apply
 * failures, and dead-letters. Skips and duplicates stay silent - they are
 * noise, and the point is that an operator reading the channel sees exactly
 * the events that need eyes. Every message carries a live campaign stats
 * line (submitted/target/remaining, open blockers) fetched at post time, so
 * the channel doubles as the campaign dashboard. Every method is
 * fire-and-forget: Slack or the stats API being down must never break the
 * apply pipeline, so failures are logged and swallowed here, at the only
 * layer that knows a post is optional.
 */
@Component
class SlackNotifier(private val client: SlackClient, private val stats: StatsClient) {

    private val log = LoggerFactory.getLogger(SlackNotifier::class.java)

    fun submission(company: String?, roleTitle: String?, source: String?) =
        post("✅ campaign: submitted to ${company ?: "?"} - $roleTitle (${source ?: "?"})")

    fun blocked(jobId: String, reason: String) =
        post("🚧 campaign: blocked $jobId - $reason")

    fun failed(jobId: String, error: String) =
        post("⚠️ campaign: apply failed $jobId - $error")

    fun deadLettered(jobId: String, error: String) =
        post("💀 campaign: dead-lettered $jobId - $error")

    private fun post(text: String) {
        val message = text + statsFooter()
        runCatching { client.postMessage(message) }
            .onFailure { log.warn("Slack post failed: {}", it.toString()) }
            .onSuccess { delivered -> if (!delivered) log.warn("Slack post not delivered: {}", message.take(80)) }
    }

    /**
     * One live campaign line per post. A stats outage returns the empty
     * string, keeping the bare outcome line - the numbers are garnish, the
     * event is the meal. `runCatching` is defense-in-depth on top of
     * `fetch()`'s own swallow-all contract, and the fetch is bounded (~10s
     * worst case) so a slow stats API only delays the post, never the loop.
     */
    private fun statsFooter(): String {
        val s = runCatching { stats.fetch() }.getOrNull() ?: return ""
        val blockers = if (s.blockersOpen > 0) " - ${s.blockersOpen} blockers open" else ""
        return "\n📊 ${s.submitted}/${s.target} submitted - ${s.remaining} remaining$blockers"
    }
}
