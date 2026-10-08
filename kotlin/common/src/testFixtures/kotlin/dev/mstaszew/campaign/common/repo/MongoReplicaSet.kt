package dev.mstaszew.campaign.common.repo

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Singleton MongoDB replica set for all integration tests in this module.
 *
 * A replica set (not a standalone mongod) is required because the recorder and
 * the importer both rely on multi-document transactions, which Mongo only offers
 * on a replica set. Testcontainers' own MongoDBContainer starts a standalone
 * server and cannot initiate a set, so this drives a GenericContainer directly.
 *
 * The set advertises `127.0.0.1:27017` as its member host, which is correct
 * inside the container but unreachable from the test JVM on the host's mapped
 * port. Every URI handed out therefore carries `directConnection=true`, so the
 * driver talks to the mapped port and skips replica-set discovery. This is safe
 * for the tests: there is exactly one member, and the probe test proves a real
 * transaction commits before any other test relies on one.
 *
 * Requires Docker: tests self-skip without it (CI runs them on every push).
 */
object MongoReplicaSet {
    private const val IMAGE = "mongo:7"
    private const val REPL_SET = "rs0"
    private const val DB_NAME = "campaign"

    /** Connection URI including the replica-set database name. */
    val connectionUri: String by lazy { start() }

    private fun start(): String {
        val container =
            GenericContainer(DockerImageName.parse(IMAGE))
                .withExposedPorts(27017)
                .withCommand("mongod", "--replSet", REPL_SET, "--bind_ip_all")
                .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
                .withStartupTimeout(Duration.ofMinutes(3))

        container.start()

        val initiate =
            container.execInContainer(
                "mongosh",
                "--quiet",
                "--eval",
                "rs.initiate({_id: '$REPL_SET', members: [{_id: 0, host: '127.0.0.1:27017'}]})",
            )
        check(initiate.exitCode == 0) {
            "rs.initiate() failed (exit ${initiate.exitCode}): ${initiate.stderr}"
        }

        return "mongodb://${container.host}:${container.getMappedPort(27017)}/$DB_NAME" +
            "?directConnection=true"
    }
}