package dev.mstaszew.campaign.common.repo

import com.mongodb.client.MongoClients
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Proves the shared test Mongo actually offers what the rest of the suite
 * depends on: a writable primary, and working multi-document transactions.
 *
 * This is the first thing to read when an integration test fails on a database
 * error. If `multi document transaction commits` fails, every recorder and
 * importer test will fail for the same reason and the harness is at fault, not
 * the code under test.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoReplicaSetIT {

    @Test
    fun `replica set reports itself as a writable primary`() {
        MongoClients.create(MongoReplicaSet.connectionUri).use { client ->
            val hello = client.getDatabase("admin").runCommand(Document("hello", 1))

            assertThat(hello.getString("setName")).isEqualTo("rs0")
            assertThat(hello.get("isWritablePrimary")).isEqualTo(true)
        }
    }

    @Test
    fun `multi document transaction commits`() {
        MongoClients.create(MongoReplicaSet.connectionUri).use { client ->
            val db = client.getDatabase("campaign")
            val first = db.getCollection("probe_first")
            val second = db.getCollection("probe_second")
            first.drop()
            second.drop()

            client.startSession().use { session ->
                session.startTransaction()
                first.insertOne(session, Document("_id", "a"))
                second.insertOne(session, Document("_id", "b"))
                session.commitTransaction()
            }

            assertThat(first.countDocuments()).isEqualTo(1)
            assertThat(second.countDocuments()).isEqualTo(1)

            first.drop()
            second.drop()
        }
    }
}