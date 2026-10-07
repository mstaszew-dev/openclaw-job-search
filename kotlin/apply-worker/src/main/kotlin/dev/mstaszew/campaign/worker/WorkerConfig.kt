package dev.mstaszew.campaign.worker

import dev.mstaszew.campaign.common.net.LlmClient
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
}
