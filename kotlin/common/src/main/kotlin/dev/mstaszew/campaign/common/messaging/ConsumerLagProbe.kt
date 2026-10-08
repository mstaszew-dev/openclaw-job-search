package dev.mstaszew.campaign.common.messaging

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Consumer-group lag, for the campaign-api stats endpoint.
 *
 * Topic creation is deliberately NOT done here. The cluster has a kafka-init
 * Job for that, because letting KafkaAdmin auto-create topics hides a typo in
 * the topic name until the first send quietly lands in a one-partition topic
 * with the default replication factor.
 */
@Component
class ConsumerLagProbe(private val adminClient: AdminClient) {

    private val log = LoggerFactory.getLogger(ConsumerLagProbe::class.java)

    /**
     * Records the consumer group sits behind, summed across partitions.
     * Returns null when lag cannot be established; a stats endpoint must not
     * fail because Kafka is briefly unreachable.
     */
    fun lag(group: String, topic: String): Long? = try {
        val described = adminClient.describeTopics(listOf(topic)).all().get()[topic]
        if (described == null) {
            null
        } else {
            val partitions = described.partitions().map { TopicPartition(topic, it.partition()) }
            val endOffsets = adminClient
                .listOffsets(partitions.associateWith { OffsetSpec.latest() })
                .all()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val committed: Map<TopicPartition, OffsetAndMetadata> = adminClient
                .listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            partitions.sumOf { tp ->
                val end = endOffsets[tp]?.offset() ?: 0L
                val current = committed[tp]?.offset() ?: 0L
                (end - current).coerceAtLeast(0L)
            }
        }
    } catch (e: Exception) {
        log.warn("consumer lag unavailable for {}: {}", topic, e.message)
        null
    }

    companion object {
        private const val TIMEOUT_SECONDS = 5L

        fun createAdmin(bootstrapServers: String): AdminClient =
            AdminClient.create(
                Properties().apply {
                    put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                    put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000")
                },
            )
    }
}