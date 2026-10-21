package dev.mstaszew.campaign.worker.apply

import dev.mstaszew.campaign.common.net.SlackClient
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Posts the hourly campaign digest to #jobcampaign: `📊 campaign: N/target
 * submitted - M remaining`, plus open blockers when any exist. Exists because
 * outcome posts are rare when the funnel is dry - without this, the channel
 * can go days without showing the count the operator actually opened Slack
 * to see. One stats dialect across the channel: the line grammar matches
 * [SlackNotifier]'s footer.
 *
 * A stats outage skips the tick entirely - unlike the outcome footer, the
 * numbers ARE the content here, so there is no bare message to fall back to;
 * the next tick retries. Like every Slack path in this service the reporter
 * is fire-and-forget: an outage is logged, never propagated out of a
 * scheduled method. The info line on success doubles as the deploy
 * verification signal (a fresh digest in the pod log within a minute of
 * rollout proves the wiring end to end).
 */
@Component
class SlackStatsReporter(private val client: SlackClient, private val stats: StatsClient) {

    private val log = LoggerFactory.getLogger(SlackStatsReporter::class.java)

    @Scheduled(
        fixedDelayString = "\${worker.stats-report-interval-seconds:3600}000",
        initialDelayString = "45000",
    )
    fun report() {
        // runCatching is defense-in-depth on top of fetch()'s own swallow-all
        // contract, mirroring SlackNotifier.statsFooter() against future drift.
        val s = runCatching { stats.fetch() }.getOrNull() ?: return
        val blockers = if (s.blockersOpen > 0) " - ${s.blockersOpen} blockers open" else ""
        val line = "📊 campaign: ${s.submitted}/${s.target} submitted - ${s.remaining} remaining$blockers"
        runCatching { client.postMessage(line) }
            .onFailure { log.warn("Slack post failed: {}", it.toString()) }
            .onSuccess { delivered ->
                if (delivered) log.info("Posted stats digest: {}", line)
                else log.warn("Slack post not delivered: {}", line)
            }
    }
}
