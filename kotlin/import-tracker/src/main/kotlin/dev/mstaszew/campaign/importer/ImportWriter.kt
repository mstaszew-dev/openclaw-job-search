package dev.mstaszew.campaign.importer

import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.BlockerEntity
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.SkipEntity
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

data class ImportReport(
    val applicationsInserted: Int,
    val applicationsSkipped: Int,
    val skipsInserted: Int,
    val skipsSkipped: Int,
    val blockersInserted: Int,
    val blockersSkipped: Int,
    val eventsInserted: Int,
)

/**
 * Idempotent JDBC writer: safe to re-run; rows already present are left alone.
 * Enum columns are written as their uppercase names to match EnumType.STRING.
 */
class ImportWriter(private val connection: Connection) {

    fun importAll(model: TrackerModel, events: List<EventEntity>): ImportReport {
        connection.autoCommit = false
        try {
            val apps = model.applications.map(::insertApplication)
            val skips = model.skips.map(::insertSkip)
            val blockers = model.blockers.map(::insertBlocker)
            val eventsInserted = if (countEvents() == 0) events.sumOf(::insertEvent) else 0
            connection.commit()
            return ImportReport(
                applicationsInserted = apps.count { it },
                applicationsSkipped = apps.count { !it },
                skipsInserted = skips.count { it },
                skipsSkipped = skips.count { !it },
                blockersInserted = blockers.count { it },
                blockersSkipped = blockers.count { !it },
                eventsInserted = eventsInserted,
            )
        } catch (e: Exception) {
            runCatching { connection.rollback() }
                .onFailure { if (it !== e) e.addSuppressed(it) }
            throw IllegalStateException("import failed, rolled back", e)
        } finally {
            connection.autoCommit = true
        }
    }

    private fun insertApplication(a: ApplicationEntity): Boolean {
        val sql = """
            INSERT INTO applications (id, source, source_job_id, company, company_key, role_title, role_key,
                url, region, remote_policy, salary, stack, apply_method, ats, status,
                confirmation_url, confirmation_text, evidence, applied_at, follow_ups, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, CAST(? AS JSONB), ?)
            ON CONFLICT (id) DO NOTHING
        """.trimIndent()
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, a.id)
            ps.setString(2, a.source)
            ps.setString(3, a.sourceJobId)
            ps.setString(4, a.company)
            ps.setString(5, a.companyKey)
            ps.setString(6, a.roleTitle)
            ps.setString(7, a.roleKey)
            ps.setString(8, a.url)
            ps.setString(9, a.region)
            ps.setString(10, a.remotePolicy)
            ps.setString(11, a.salary)
            ps.setArray(12, a.stack.takeIf { it.isNotEmpty() }?.let(::textArray))
            ps.setString(13, a.applyMethod)
            ps.setString(14, a.ats)
            ps.setString(15, a.status.name)
            ps.setString(16, a.confirmationUrl)
            ps.setString(17, a.confirmationText)
            ps.setString(18, a.evidence)
            ps.setTimestamp(19, a.appliedAt?.toTimestamp())
            ps.setString(20, a.followUps)
            ps.setString(21, a.notes)
            return ps.executeUpdate() == 1
        }
    }

    private fun insertSkip(s: SkipEntity): Boolean {
        val sql = """
            INSERT INTO skips (reason, source, source_job_id, company, company_key, role_title, role_key,
                url, region, remote_policy, salary, stack, detail, blocked_repeat, block_count, at)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM skips
                 WHERE reason = ?
                   AND source IS NOT DISTINCT FROM ?
                   AND source_job_id IS NOT DISTINCT FROM ?
                   AND company_key IS NOT DISTINCT FROM ?
                   AND url IS NOT DISTINCT FROM ?
                   AND at IS NOT DISTINCT FROM ?
            )
        """.trimIndent()
        connection.prepareStatement(sql).use { ps ->
            var i = 0
            ps.setString(++i, s.reason.name)
            ps.setString(++i, s.source)
            ps.setString(++i, s.sourceJobId)
            ps.setString(++i, s.company)
            ps.setString(++i, s.companyKey)
            ps.setString(++i, s.roleTitle)
            ps.setString(++i, s.roleKey)
            ps.setString(++i, s.url)
            ps.setString(++i, s.region)
            ps.setString(++i, s.remotePolicy)
            ps.setString(++i, s.salary)
            ps.setArray(++i, s.stack.takeIf { it.isNotEmpty() }?.let(::textArray))
            ps.setString(++i, s.detail)
            ps.setBoolean(++i, s.blockedRepeat)
            ps.setIntOrNull(++i, s.blockCount)
            ps.setTimestamp(++i, s.at.toTimestamp())
            ps.setString(++i, s.reason.name)
            ps.setString(++i, s.source)
            ps.setString(++i, s.sourceJobId)
            ps.setString(++i, s.companyKey)
            ps.setString(++i, s.url)
            ps.setTimestamp(++i, s.at.toTimestamp())
            return ps.executeUpdate() == 1
        }
    }

    private fun insertBlocker(b: BlockerEntity): Boolean {
        val sql = """
            INSERT INTO blockers (source, source_job_id, company, company_key, role_title, url, reason, resolved, detail, at)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM blockers
                 WHERE company_key IS NOT DISTINCT FROM ?
                   AND reason IS NOT DISTINCT FROM ?
                   AND at IS NOT DISTINCT FROM ?
            )
        """.trimIndent()
        connection.prepareStatement(sql).use { ps ->
            var i = 0
            ps.setString(++i, b.source)
            ps.setString(++i, b.sourceJobId)
            ps.setString(++i, b.company)
            ps.setString(++i, b.companyKey)
            ps.setString(++i, b.roleTitle)
            ps.setString(++i, b.url)
            ps.setString(++i, b.reason)
            ps.setBoolean(++i, b.resolved)
            ps.setString(++i, b.detail)
            ps.setTimestamp(++i, b.at.toTimestamp())
            ps.setString(++i, b.companyKey)
            ps.setString(++i, b.reason)
            ps.setTimestamp(++i, b.at.toTimestamp())
            return ps.executeUpdate() == 1
        }
    }

    private fun insertEvent(e: EventEntity): Int {
        val sql = """
            INSERT INTO events (at, action, record)
            VALUES (?, ?, CAST(? AS JSONB))
        """.trimIndent()
        connection.prepareStatement(sql).use { ps ->
            ps.setTimestamp(1, e.at.toTimestamp())
            ps.setString(2, e.action)
            ps.setString(3, e.record)
            return ps.executeUpdate()
        }
    }

    private fun countEvents(): Int =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM events").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun textArray(values: List<String>): java.sql.Array =
        connection.createArrayOf("text", values.toTypedArray())

    private fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)
}

private fun java.sql.PreparedStatement.setIntOrNull(index: Int, value: Int?) {
    if (value == null) setNull(index, java.sql.Types.INTEGER) else setInt(index, value)
}
