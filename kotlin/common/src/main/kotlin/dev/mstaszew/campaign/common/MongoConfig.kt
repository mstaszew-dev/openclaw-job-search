package dev.mstaszew.campaign.common

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.bson.Document
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.convert.MongoCustomConversions
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

    @Bean
    fun mongoCustomConversions(): MongoCustomConversions =
        MongoCustomConversions.create { store ->
            store.registerConverter(JsonNodeToBson(NODE_MAPPER))
            store.registerConverter(BsonToJsonNode(NODE_MAPPER))
        }

    /**
     * `salary`, `evidence`, `followUps` and `EventEntity.record` hold real
     * nested documents rather than the unparsed strings the Postgres mapping
     * needed, so they can be queried by field ("salary.min") and not just read
     * back whole.
     *
     * Spring Data has no built-in Jackson support: it reflects over the
     * property type, tries to call `JsonNode()` and fails with
     * "Failed to instantiate [com.fasterxml.jackson.databind.JsonNode]: Class is
     * abstract" while building the mapping context. These two converters are
     * what make the type usable at all.
     *
     * Writing goes through the driver's own Document/List/Scalar shapes, so a
     * stored field stays queryable. Reading turns those shapes back into
     * JsonNode through the application mapper, which is the same instance the
     * rest of the code uses.
     */
    private class JsonNodeToBson(private val mapper: ObjectMapper) : Converter<JsonNode, Any> {
        override fun convert(source: JsonNode): Any? = when {
            source.isNull -> null
            // Document.parse gives the driver a real subdocument, so
            // "salary.min" is a queryable path rather than a string.
            source.isObject -> Document.parse(source.toString())
            source.isArray -> mapper.convertValue(source, List::class.java)
            source.isNumber -> source.decimalValue()
            source.isBoolean -> source.booleanValue()
            else -> source.asText()
        }
    }

    private class BsonToJsonNode(private val mapper: ObjectMapper) : Converter<Any, JsonNode> {
        override fun convert(source: Any): JsonNode = mapper.valueToTree(source)
    }

    private companion object {
        /**
         * Deliberately not the application's ObjectMapper bean. The Mongo slice
         * in common has no Jackson auto-configuration, so injecting one fails
         * with "No qualifying bean of type ObjectMapper". These converters only
         * move JsonNode to and from plain BSON shapes, so a private mapper with
         * no modules registered is exactly as capable as it needs to be: the
         * Instant fields on the documents around them are mapped by Spring
         * Data, not by Jackson.
         */
        val NODE_MAPPER: ObjectMapper = ObjectMapper()
    }
}