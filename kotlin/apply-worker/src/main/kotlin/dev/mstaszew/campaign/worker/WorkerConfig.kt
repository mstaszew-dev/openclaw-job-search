package dev.mstaszew.campaign.worker

import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.net.SlackClient
import dev.mstaszew.campaign.worker.apply.StatsClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerConfig {

    @Bean
    fun llmClient(props: WorkerProperties) = LlmClient(
        baseUrl = props.msrouterUrl,
        apiKey = props.msrouterApiKey,
        model = props.msrouterModel,
    )

    /**
     * Credentials arrive through the environment (secretKeyRef in worker.yaml
     * maps the campaign-msrouter secret); nulls are legal and mean silence.
     * Env is read here, at the explicit wiring site, like every other
     * environment knob in this service.
     */
    @Bean
    fun slackClient() = SlackClient(
        botToken = System.getenv("SLACK_BOT_TOKEN"),
        channel = System.getenv("SLACK_CHANNEL"),
        webhookUrl = System.getenv("SLACK_WEBHOOK"),
    )

    /** In-cluster campaign-api for the Slack stats footer; default is the cluster DNS name. */
    @Bean
    fun statsClient() = StatsClient(
        baseUrl = System.getenv("CAMPAIGN_API_URL") ?: "http://campaign-api.campaign.svc.cluster.local:8080",
    )
}
