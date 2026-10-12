package dev.mstaszew.campaign.common.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.util.backoff.FixedBackOff
import org.springframework.util.backoff.FixedBackOff.UNLIMITED_ATTEMPTS

/**
 * Producer and consumer wiring for the campaign pipeline.
 *
 * The consumer settings are load-bearing, not defaults:
 *
 * - `enable.auto.commit=false` with MANUAL_IMMEDIATE ack, because the commit
 *   has to happen AFTER the Mongo write. Auto-commit would commit the offset
 *   at poll time, so a crash between "message handed to us" and "outcome
 *   recorded" would silently lose the job.
 * - `max.poll.records=1`, because one apply drives one browser tab; a batch
 *   would have one slow apply blocking its neighbours behind the same poll.
 * - `max.poll.interval.ms` sized from the agent loop's worst case, which is
 *   two round-trips per step (an LLM call plus a Playwright tool call), not
 *   one. Getting this wrong is silent and expensive: the broker evicts the
 *   consumer mid-apply and redelivers the message while it is still running,
 *   so two agents drive the same employer at once. It is asserted against the
 *   real BrowserAgent constants in apply-worker's PollBudgetTest so it cannot
 *   silently drift; this module cannot depend on the worker, so the numbers are
 *   mirrored below and checked against the originals there.
 *
 * Type headers are disabled on both sides and the application's ObjectMapper is
 * handed to the serializer instances explicitly, so JobMessage carries an
 * Instant (JSR-310) in its payload rather than the serializer quietly creating
 * a second, differently configured mapper.
 */
@Configuration(proxyBeanMethods = false)
class KafkaWiring {

    /** Type headers off, so JobMessage is deserialised as JobMessage. */
    private fun valueSerializer(mapper: ObjectMapper): JsonSerializer<Any> =
        JsonSerializer<Any>(mapper).apply {
            noTypeInfo()
        }

    private fun valueDeserializer(mapper: ObjectMapper): JsonDeserializer<JobMessage> =
        JsonDeserializer(JobMessage::class.java, mapper, false)

    @Bean
    fun campaignProducerFactory(
        properties: KafkaProperties,
        kafkaObjectMapper: ObjectMapper,
    ): ProducerFactory<String, Any> =
        DefaultKafkaProducerFactory(
            // Bound from spring.kafka.* first so the timeouts in the application
            // yamls actually reach the producer, then overridden by the lines
            // this class owns. A hand-built map that ignores the bound properties
            // silently ran on the Kafka defaults instead.
            properties.buildProducerProperties(null).apply {
                put(
                    // Only the key serializer goes in the config map. Kafka's
                    // AbstractConfig accepts a Class or a class NAME for a
                    // *_CLASS_CONFIG key and nothing else, so a pre-configured
                    // JsonSerializer instance has to be handed to the factory
                    // constructor instead; putting the instance in the map fails
                    // at producer creation with "Expected a Class instance or
                    // class name".
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    StringSerializer::class.java,
                )
            },
            StringSerializer(),
            valueSerializer(kafkaObjectMapper),
        )

    @Bean
    fun campaignKafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> =
        KafkaTemplate(producerFactory)

    @Bean
    fun campaignAdminClient(
        @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
    ): AdminClient = ConsumerLagProbe.createAdmin(bootstrapServers)

    @Bean("applyKafkaListenerContainerFactory")
    fun applyKafkaListenerContainerFactory(
        properties: KafkaProperties,
        kafkaObjectMapper: ObjectMapper,
    ): ConcurrentKafkaListenerContainerFactory<String, JobMessage> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, JobMessage>()
        factory.consumerFactory = DefaultKafkaConsumerFactory(
            properties.buildConsumerProperties(null).apply {
                put(ConsumerConfig.GROUP_ID_CONFIG, Topics.APPLY_GROUP)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)
                put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, MAX_POLL_INTERVAL_MS)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            },
            StringDeserializer(),
            valueDeserializer(kafkaObjectMapper),
        ).apply {
            // Keep the JsonDeserializer exactly as built above. Left on,
            // Spring re-configures it from the consumer properties, which no
            // longer carry the target type or the mapper.
            setConfigureDeserializers(false)
        }
        factory.setConcurrency(1)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
        // WorkerLoop dead-letters on job_state.attempts itself and handles every
        // exception it raises, so the recoverer here is a backstop that must not
        // fire at all. Left unset, the container's implicit DefaultErrorHandler
        // retries ten times and then LOGS AND SKIPS, committing the offset for a
        // message whose outcome was never recorded: a silent job loss. Unlimited
        // attempts makes the handler propagate instead, and the offset stays
        // uncommitted for the broker to redeliver.
        factory.setCommonErrorHandler(
            DefaultErrorHandler(FixedBackOff(REDELIVERY_BACKOFF_MS, UNLIMITED_ATTEMPTS)),
        )
        return factory
    }

    companion object {
        /** Short pause between container-driven redeliveries of an unexpected throw. */
        const val REDELIVERY_BACKOFF_MS = 1_000L

        /**
         * Worst case of one apply: MAX_STEPS iterations, each of which waits up
         * to STEP_TIMEOUT on the model and then up to TOOL_TIMEOUT on
         * Playwright. 25 x (90 + 120) = 5250s, plus 10% margin, rounded up to
         * whole minutes.
         *
         * The constants live in BrowserAgent (apply-worker), which this module
         * cannot depend on, so they are duplicated here as numbers and the
         * relationship between the two is pinned by PollBudgetTest.
         */
        const val MAX_POLL_INTERVAL_MS = 5_820_000

        /** The agent loop bounds this budget must cover, mirrored for the test. */
        const val AGENT_MAX_STEPS = 25
        const val AGENT_STEP_TIMEOUT_MS = 90_000L
        const val AGENT_TOOL_TIMEOUT_MS = 120_000L

        /** What the loop can actually take, with the margin applied. */
        val AGENT_WORST_CASE_MS: Long =
            AGENT_MAX_STEPS * (AGENT_STEP_TIMEOUT_MS + AGENT_TOOL_TIMEOUT_MS)
    }
}