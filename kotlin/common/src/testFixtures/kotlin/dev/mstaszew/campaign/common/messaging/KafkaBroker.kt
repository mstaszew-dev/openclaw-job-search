package dev.mstaszew.campaign.common.messaging

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.Properties

/**
 * Single-node KRaft broker for the integration tests.
 *
 * Uses the official Testcontainers Kafka module rather than a hand-rolled
 * GenericContainer with apache/kafka env vars: getting KAFKA_PROCESS_ROLES, the
 * controller listener names and the cluster id right by hand is a well-known
 * way to waste an afternoon, and the module also waits for the broker to accept
 * a real metadata request rather than merely opening the port.
 *
 * Lazy, like MongoReplicaSet: on a machine without Docker (the dev Mac) the
 * object is never touched, so the tests that need it self-skip via
 * @Testcontainers(disabledWithoutDocker = true).
 */
object KafkaBroker {

    private val container: KafkaContainer by lazy {
        KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"))
            .withStartupTimeout(Duration.ofMinutes(3))
    }

    /**
     * Starts the broker on first touch.
     *
     * `@Testcontainers` only starts containers declared as `@Container` fields on
     * the test class; this is a shared singleton reached through
     * `@DynamicPropertySource`, so nothing else starts it. `getBootstrapServers()`
     * reads a mapped port and throws "Mapped port can only be obtained after the
     * container is started" unless we start it ourselves. Kept lazy so a machine
     * without Docker never touches it and the tests self-skip.
     */
    private val started: Boolean by lazy {
        container.start()
        true
    }

    val bootstrapServers: String
        get() {
            started
            return container.bootstrapServers
        }

    /** Creates topics up front so a test does not depend on auto-creation. */
    fun ensureTopics(vararg names: String) {
        admin().use { admin ->
            val existing = admin.listTopics().names().get()
            val missing = names.filterNot { it in existing }
            if (missing.isNotEmpty()) {
                admin.createTopics(
                    missing.map { NewTopic(it, TOPIC_PARTITIONS, REPLICATION) },
                ).all().get()
            }
        }
    }

    fun admin(): AdminClient = AdminClient.create(
        Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000")
        },
    )

    private const val TOPIC_PARTITIONS = 1
    private const val REPLICATION: Short = 1
}