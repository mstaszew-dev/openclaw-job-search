package dev.mstaszew.campaign.finder

import dev.mstaszew.campaign.finder.score.CvScore
import dev.mstaszew.campaign.finder.score.CvScorer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/**
 * Guards the wiring the pause depends on: exactly one CvScorer bean, built from
 * FinderProperties, and the property really reaching the scorer. A rename or a
 * stray second definition must fail here rather than silently leave scoring on.
 */
class FinderConfigWiringTest {

    /** Only the scorer beans; no Mongo, Kafka or web context. */
    @Configuration
    @EnableConfigurationProperties(FinderProperties::class)
    class ScorerOnlyConfig {
        @org.springframework.context.annotation.Bean
        fun llmClient(props: FinderProperties) =
            dev.mstaszew.campaign.common.net.LlmClient(
                baseUrl = props.msrouterUrl,
                apiKey = props.msrouterApiKey,
                model = props.msrouterModel,
            )

        @org.springframework.context.annotation.Bean
        fun cvScorer(llmClient: dev.mstaszew.campaign.common.net.LlmClient, props: FinderProperties) =
            CvScorer(llmClient, scoringEnabled = props.scoringEnabled)
    }

    private fun runner(vararg properties: String) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of())
        .withUserConfiguration(ScorerOnlyConfig::class.java)
        .withPropertyValues(*properties)

    private fun score(scorer: CvScorer) = scorer.score(
        company = "Acme",
        roleTitle = "Senior Java Developer",
        stack = listOf("java"),
        salaryText = null,
        applicantCvProfile = "Java/Spring",
    )

    @Test
    fun `exactly one scorer bean is built from the properties`() {
        // No scoring here: with scoring on, CvScorer would call the gateway,
        // and this test must not depend on a reachable one.
        runner().run { context ->
            assertThat(context).hasSingleBean(CvScorer::class.java)
        }
    }

    @Test
    fun `scoring-enabled false pauses scoring`() {
        runner("finder.scoring-enabled=false").run { context ->
            assertThat(context).hasSingleBean(CvScorer::class.java)
            val result = score(context.getBean(CvScorer::class.java))
            assertThat(result.source).isEqualTo(CvScore.Source.FALLBACK)
            assertThat(result.reason).contains("scoring paused")
        }
    }
}
