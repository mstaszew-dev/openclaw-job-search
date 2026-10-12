package dev.mstaszew.campaign.api

import dev.mstaszew.campaign.common.messaging.KafkaBroker
import dev.mstaszew.campaign.common.repo.MongoReplicaSet
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ApiContextIT {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun infra(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") { MongoReplicaSet.connectionUri }
            registry.add("spring.data.mongodb.database") { "api_context_it" }
            registry.add("spring.kafka.bootstrap-servers") { KafkaBroker.bootstrapServers }
        }
    }

    @Test
    fun contextLoads() {
    }
}
