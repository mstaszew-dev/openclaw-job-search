package dev.mstaszew.campaign.worker.apply

import dev.mstaszew.campaign.common.net.SlackClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Posts campaign outcomes to #jobcampaign: wins, new portal blocks, apply
 * failures, and dead-letters. Skips and duplicates stay silent - they are
 * noise, and the point is that an operator reading the channel sees exactly
 * the events that need eyes. Every method is fire-and-forget: Slack being
 * down must never break the apply pipeline, so failures are logged and
 * swallowed here, at the only layer that knows a post is optional.
 */
@Component
class SlackNotifier(private val client: SlackClient) {

    private val log = LoggerFactory.getLogger(SlackNotifier::class.java)

    fun submission(company: String?, roleTitle: String?, source: String?) =
        post("✅ campaign: submitted to ${company ?: "?"} - $roleTitle ($source)")

    fun blocked(jobId: String, reason: String) =
        post("🚧 campaign: blocked $jobId - $reason")

    fun failed(jobId: String, error: String) =
        post("⚠️ campaign: apply failed $jobId - $error")

    fun deadLettered(jobId: String, error: String) =
        post("💀 campaign: dead-lettered $jobId - $error")

    private fun post(text: String) {
        runCatching { client.postMessage(text) }
            .onFailure { log.warn("Slack post failed: {}", it.toString()) }
            .onSuccess { delivered -> if (!delivered) log.warn("Slack post not delivered: {}", text.take(80)) }
    }
}
