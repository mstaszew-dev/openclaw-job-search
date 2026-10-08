package dev.mstaszew.campaign.common.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerializer

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
 *   agent constants in KafkaWiringTest so it cannot silently drift.
 *
 * Type headers are disabled on both sides and the application's ObjectMapper is
 * handed to the serializer instances explicitly, so JobMessage carries an
 * Instant (JSR-310) in its payload rather than the serializer quietly creating
 * a second, differently configured mapper.
 */
@Configuration(proxyBeanMethods = false)
class KafkaWiring {

    @Bean
    fun campaignProducerFactory(
        @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
        kafkaObjectMapper: ObjectMapper,
    ): ProducerFactory<String, Any> =
        DefaultKafkaProducerFactory(
            mapOf<String, Any>(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer<Any>(kafkaObjectMapper),
                JsonSerializer.ADD_TYPE_INFO_HEADERS to false,
            ),
        )

    @Bean
    @ConditionalOnMissingBean(name = ["kafkaTemplate"])
    fun campaignKafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> =
        KafkaTemplate(producerFactory)

    @Bean
    @ConditionalOnMissingBean(AdminClient::class)
    fun campaignAdminClient(
        @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
    ): AdminClient = ConsumerLagProbe.createAdmin(bootstrapServers)

    @Bean("applyKafkaListenerContainerFactory")
    fun applyKafkaListenerContainerFactory(
        @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
        kafkaObjectMapper: ObjectMapper,
    ): ConcurrentKafkaListenerContainerFactory<String, Any> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, Any>()
        factory.consumerFactory = DefaultKafkaConsumerFactory(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to Topics.APPLY_GROUP,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to
                    JsonDeserializer(JobMessage::class.java, kafkaObjectMapper, false),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1,
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to MAX_POLL_INTERVAL_MS,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
        )
        factory.setConcurrency(1)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
        return factory
    }

    companion object {
        /**
         * Worst case of one apply: MAX_STEPS iterations, each of which waits up
         * to STEP_TIMEOUT on the model and then up to TOOL_TIMEOUT on
         * Playwright. 25 x (90 + 120) = 5250s, plus 10% margin, rounded up to
         * whole minutes.
         *
         * The constants live in BrowserAgent (apply-worker), which this module
         * cannot depend on, so they are duplicated here as numbers and the
         * relationship between the two is pinned by KafkaWiringTest.
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