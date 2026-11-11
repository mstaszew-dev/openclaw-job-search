package dev.mstaszew.campaign.finder

import dev.mstaszew.campaign.finder.score.CvScore
import dev.mstaszew.campaign.finder.score.CvScorer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/**
 * Guards the wiring the pause depends on: exactly one CvScorer bean, built by
 * the PRODUCTION FinderConfig from FinderProperties, and the properties
 * really reaching the scorer. A rename or a stray second definition must
 * fail here rather than silently leave scoring on.
 *
 * Review S1, 2026-11-11: this used to run a hand-copied mirror of the config,
 * so a regression in the production bean (a hardcoded timeout, a dropped
 * param) would have shipped silently - the same drift class as the Nov 4
 * finder jar missing the Nov 5 regrind branch. The real config runs fine in
 * a bare context: its beans need only the two property classes, and the MCP
 * client process is spawned lazily per discovery run, never at bean
 * creation.
 */
class FinderConfigWiringTest {

    /** The production config itself; no Mongo, Kafka or web context. */
    private fun runner(vararg properties: String) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of())
        .withUserConfiguration(FinderConfig::class.java)
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

    /**
     * The timeout property must reach the PRODUCTION bean, not just bind:
     * against the old mirror config, a hardcoded timeout in
     * FinderConfig.cvScorer would have passed a properties-only assertion.
     */
    @Test
    fun `production wiring passes the property-driven timeout to the scorer bean`() {
        runner("finder.scorer-timeout-seconds=25").run { context ->
            assertThat(context).hasSingleBean(CvScorer::class.java)
            assertThat(context.getBean(CvScorer::class.java).requestTimeout)
                .isEqualTo(Duration.ofSeconds(25))
        }
    }
}
