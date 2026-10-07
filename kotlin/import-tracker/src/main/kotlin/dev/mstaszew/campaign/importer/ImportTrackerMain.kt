package dev.mstaszew.campaign.importer

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * One-time (idempotent) importer of the live Python campaign state into the
 * distributed system's Postgres. Run before enabling workers:
 *
 *   DB_URL=... DB_USER=... DB_PASSWORD=... \
 *     ./gradlew :import-tracker:run --args="<tracker.json> [events.jsonl]"
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: import-tracker <tracker.json> [events.jsonl]")
        exitProcess(2)
    }
    val trackerPath = Path.of(args[0])
    val eventsPath = args.getOrNull(1)?.let(Path::of)
    if (!trackerPath.toFile().canRead()) {
        System.err.println("cannot read ${trackerPath.toAbsolutePath()}")
        exitProcess(2)
    }

    val dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/campaign"
    val dbUser = System.getenv("DB_USER") ?: "campaign"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "campaign"

    val mapper = ObjectMapper()
    val model = TrackerParser(mapper).parse(trackerPath.toFile().readText())
    val events = eventsPath
        ?.takeIf { it.toFile().canRead() }
        ?.let { EventsParser(mapper).parse(it.toFile().readText()) }
        ?: emptyList()

    DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
        val report = ImportWriter(connection).importAll(model, events)
        model.warnings.forEach { System.err.println("WARNING: $it") }
        println(
            """
            import report:
              applications: ${report.applicationsInserted} inserted, ${report.applicationsSkipped} already present (tracker applications[]: ${model.applications.size})
              skips:        ${report.skipsInserted} inserted, ${report.skipsSkipped} already present (tracker skipped[]: ${model.skips.size})
              blockers:     ${report.blockersInserted} inserted, ${report.blockersSkipped} already present (tracker blockers[]: ${model.blockers.size})
              events:       ${report.eventsInserted} inserted
            tracker stats: submitted=${model.stats.submitted}, skippedDuplicate=${model.stats.skippedDuplicate},
                           skippedSalary=${model.stats.skippedSalary}, skippedFilter=${model.stats.skippedFilter},
                           blockedManual=${model.stats.blockedManual}
            """.trimIndent(),
        )
    }
}
