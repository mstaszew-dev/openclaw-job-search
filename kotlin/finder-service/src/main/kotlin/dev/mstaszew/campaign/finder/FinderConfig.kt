package dev.mstaszew.campaign.finder

import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.finder.collect.ListingCollector
import dev.mstaszew.campaign.finder.collect.McpListingCollector
import dev.mstaszew.campaign.finder.policy.EligibilityPolicy
import dev.mstaszew.campaign.finder.policy.EligibilityProperties
import dev.mstaszew.campaign.finder.score.CvScorer
import dev.mstaszew.campaign.common.net.LlmClient
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
@EnableConfigurationProperties(EligibilityProperties::class, FinderProperties::class)
class FinderConfig {

    @Bean
    fun eligibilityPolicy(props: EligibilityProperties) = EligibilityPolicy(props)

    @Bean
    fun llmClient(props: FinderProperties) = LlmClient(
        baseUrl = props.msrouterUrl,
        apiKey = props.msrouterApiKey,
        model = props.msrouterModel,
    )

    @Bean
    fun cvScorer(llmClient: LlmClient, props: FinderProperties): CvScorer {
        CvScorer.logPausedState(props.scoringEnabled)
        return CvScorer(llmClient, scoringEnabled = props.scoringEnabled)
    }

    /** Opens (and closes) a fresh MCP server process per discovery run. */
    @Bean
    fun listingCollector(props: FinderProperties): ListingCollector = ListingCollector { source ->
        McpListingCollector(
            clientFactory = { McpStdioClient(props.mcpCommand.split(' ')) },
            stacks = props.collectStacks,
        ).collect(source)
    }
}
