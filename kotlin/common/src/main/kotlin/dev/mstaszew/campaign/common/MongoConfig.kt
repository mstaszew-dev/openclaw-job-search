package dev.mstaszew.campaign.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories

/**
 * Mongo wiring for every service (picked up via component scan). Kept off the
 * application classes so @WebMvcTest slices do not drag in repositories and
 * demand a MongoClient.
 *
 * The transaction manager requires a replica set: on a standalone mongod
 * `MongoTransactionManager` exists but any @Transactional method fails at
 * commit with "Transaction numbers are only allowed on a replica set member".
 * Both the cluster manifest (rs0) and the Testcontainers harness in
 * common/src/test/.../repo/MongoReplicaSet.kt start a replica set for that
 * reason.
 */
@Configuration(proxyBeanMethods = false)
@EnableMongoRepositories("dev.mstaszew.campaign")
class MongoConfig {

    @Bean
    fun mongoTransactionManager(factory: MongoDatabaseFactory): MongoTransactionManager =
        MongoTransactionManager(factory)
}