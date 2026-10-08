package dev.mstaszew.campaign.common.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The poll interval has to cover one entire worst-case apply, and nothing else
 * in the system enforces that. Too low and the broker evicts the consumer
 * mid-apply and redelivers the message while it is still being worked on,
 * which is silent (the logs just show two attempts) and, in live mode, means
 * two browser agents submit to the same employer. Too high and a genuinely
 * dead consumer is never replaced.
 *
 * Pure arithmetic, so it runs without Docker.
 */
class KafkaWiringTest {

    @Test
    fun `poll interval covers the worst case apply loop`() {
        assertThat(KafkaWiring.MAX_POLL_INTERVAL_MS.toLong())
            .isGreaterThan(KafkaWiring.AGENT_WORST_CASE_MS)
    }

    @Test
    fun `poll interval leaves headroom but does not sit near the default`() {
        // At least 10% margin over the theoretical worst case, so an ordinary
        // slow step does not put the consumer near eviction.
        assertThat(KafkaWiring.MAX_POLL_INTERVAL_MS.toLong())
            .isGreaterThanOrEqualTo((KafkaWiring.AGENT_WORST_CASE_MS * 1.1).toLong())
        // And it must be nowhere near the 5-minute Kafka default that this
        // whole configuration exists to override.
        assertThat(KafkaWiring.MAX_POLL_INTERVAL_MS.toLong()).isGreaterThan(300_000L * 10)
    }

    @Test
    fun `the message id is the shared dedupe keyspace with the python tracker`() {
        val message = JobMessage(
            source = "justjoin",
            sourceJobId = "ab12",
            company = "Acme",
            companyKey = "acme",
            roleTitle = "Kotlin Developer",
            url = "https://justjoin.it/offers/kotlin-dev?jobid=ab12",
            region = "PL",
            score = 80,
            scoreReason = "kotlin",
            enqueuedAt = java.time.Instant.EPOCH,
        )
        assertThat(message.id).isEqualTo("justjoin:ab12")
    }
}