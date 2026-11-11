package dev.mstaszew.campaign.common.repo

import com.mongodb.client.result.UpdateResult
import dev.mstaszew.campaign.common.domain.JobStateDocument
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant
import java.util.regex.Pattern

/**
 * The query we emit is the contract with Mongo, and this contract has one
 * property a Java-side test cannot see: `$regex` never matches a document
 * where the field is absent, while `$not` does. Eight production rows were
 * FAILED with no `lastError` field at all (2026-11-11) and the old
 * negative-lookahead regex hid them from the re-grind pass forever.
 *
 * No Spring context and no Docker here: the behavioral proof against real
 * Mongo lives in JobStateRepositoryRegrindIT and runs in CI.
 */
class JobStateRepositoryRegrindQueryTest {

    private val mongo = mockk<MongoTemplate>()
    private val repo = JobStateRepositoryImpl(mongo)

    /** Every `lastError` guard in the emitted query, wherever it is nested. */
    private fun lastErrorGuards(node: Any?): List<Any?> = when (node) {
        is Document -> node.entries.flatMap { (key, value) ->
            if (key == "lastError") listOf(value) else lastErrorGuards(value)
        }
        is List<*> -> node.flatMap { lastErrorGuards(it) }
        else -> emptyList()
    }

    /**
     * Spring stores a plain `.regex(...)` as a String and a `.not().regex(...)`
     * as a nested document or a Pattern depending on the version, so compare
     * the guard by what it means rather than by its carrier type.
     */
    private fun guardText(guard: Any?): String = when (guard) {
        is Document -> guard.toJson()
        is Pattern -> guard.pattern()
        is String -> guard
        else -> guard.toString()
    }

    private fun assertBlockedGuardIsNotRegex(query: Query) {
        val guards = lastErrorGuards(query.queryObject).map { guardText(it) }

        // FAILED branch: the blocked prefix wrapped in $not, so a document
        // with no lastError field at all still matches.
        assertThat(guards).anySatisfy {
            assertThat(it).contains("\$not")
            assertThat(it).contains("^blocked:")
        }
        // DEAD branch keeps its model-flake prefix guard, untouched.
        assertThat(guards).contains("^model-flake failure")
        // No negative lookahead survives: that form silently skips missing
        // fields, which is exactly the bug this test pins.
        assertThat(guards).noneMatch { it.contains("(?!") }
    }

    @Test
    fun `failed candidate branch uses not-regex so a missing lastError still matches`() {
        val query = slot<Query>()
        every { mongo.find(capture(query), JobStateDocument::class.java) } returns emptyList()

        repo.findRegrindCandidates(
            maxAttemptsParam = 3,
            olderThan = Instant.parse("2026-11-11T00:00:00Z"),
            strandedAfter = Instant.parse("2026-11-09T00:00:00Z"),
            limit = 10,
        )

        assertBlockedGuardIsNotRegex(query.captured)
    }

    @Test
    fun `claim mirrors the candidate guard for failed rows`() {
        val query = slot<Query>()
        every {
            mongo.updateFirst(capture(query), any<Update>(), JobStateDocument::class.java)
        } returns UpdateResult.acknowledged(1L, 1L, null)

        repo.regrindClaim(
            id = "justjoin:x",
            maxAttempts = 3,
            olderThan = Instant.parse("2026-11-11T00:00:00Z"),
            strandedAfter = Instant.parse("2026-11-09T00:00:00Z"),
        )

        assertBlockedGuardIsNotRegex(query.captured)
    }
}
