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

        // rs.initiate() returns the moment the config is accepted, which is
        // before the member has been elected. Handing the URI back at that
        // point hands it to a SECONDARY, and the first write in the test JVM
        // then blocks on server selection until it gives up. Waiting for the
        // real primary is what makes the harness deterministic.
        awaitWritablePrimary(container)

        return "mongodb://${container.host}:${container.getMappedPort(27017)}/$DB_NAME" +
            "?directConnection=true"
    }

    private fun awaitWritablePrimary(container: GenericContainer<*>) {
        val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
        var lastSeen = "never evaluated"
        while (System.nanoTime() < deadline) {
            val probe =
                container.execInContainer(
                    "mongosh",
                    "--quiet",
                    "--eval",
                    "db.hello().isWritablePrimary",
                )
            lastSeen = probe.stdout.trim().ifEmpty { probe.stderr.trim() }
            if (probe.exitCode == 0 && lastSeen == "true") return
            Thread.sleep(250)
        }
        error("replica set $REPL_SET never reached a writable primary; last probe: $lastSeen")
    }
}