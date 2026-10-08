package dev.mstaszew.campaign.worker

import dev.mstaszew.campaign.common.messaging.KafkaBroker
import dev.mstaszew.campaign.common.repo.MongoReplicaSet
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    // Boot the real WorkerLoop, but not its listener. The Kafka broker is a
    // JVM-wide singleton, so a running consumer here would join the
    // apply-workers group alongside DoubleApplyGuardIT and steal the messages
    // that test publishes, against a different database. This test is about
    // wiring, not about consuming.
    properties = ["spring.kafka.listener.auto-startup=false"],
)
class WorkerContextIT {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun infra(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") { MongoReplicaSet.connectionUri }
            registry.add("spring.data.mongodb.database") { "worker_context_it" }
            registry.add("spring.kafka.bootstrap-servers") { KafkaBroker.bootstrapServers }
        }
    }

    @Test
    fun contextLoads() {
    }
}
