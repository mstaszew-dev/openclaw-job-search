package dev.mstaszew.campaign.common.repo

import com.mongodb.client.result.UpdateResult
import dev.mstaszew.campaign.common.domain.JobStateDocument
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonRegularExpression
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
 * The carrier type is part of the contract too: Mongo accepts only a regex or
 * a document under `$not`, so a plain String payload would be rejected at
 * runtime even though it contains the right text. This test pins both the
 * `$not` shape and the regex carrier, because it is the only local gate (the
 * behavioral proof against real Mongo lives in JobStateRepositoryRegrindIT and
 * runs in CI).
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

    /** The regex text of a carrier Spring is allowed to emit for a regex. */
    private fun regexText(carrier: Any?): String? = when (carrier) {
        is Pattern -> carrier.pattern()
        is BsonRegularExpression -> carrier.pattern
        else -> null
    }

    private fun assertBlockedGuardIsNotRegex(query: Query) {
        val guards = lastErrorGuards(query.queryObject)

        // Exactly two guards: the FAILED branch and the untouched DEAD branch.
        assertThat(guards).hasSize(2)

        // FAILED branch: `$not` wrapping a real regex carrier. A bare regex
        // would silently skip absent fields (the bug); a String payload would
        // be rejected by Mongo at runtime.
        val blockedGuard = guards.filterIsInstance<Document>().single()
        assertThat(blockedGuard.keys).containsExactly("\$not")
        assertThat(regexText(blockedGuard["\$not"]))
            .describedAs("\$not must carry a regex carrier, not a plain String")
            .isEqualTo("^blocked:")

        // DEAD branch keeps its model-flake prefix guard, untouched: a bare
        // regex carrier with no $not wrapper.
        val deadGuard = guards.filterNot { it is Document }.single()
        assertThat(regexText(deadGuard)).isEqualTo("^model-flake failure")

        // No negative lookahead survives: that form silently skips missing
        // fields, which is exactly the bug this test pins.
        assertThat(guards.mapNotNull { if (it is Document) regexText(it["\$not"]) else regexText(it) })
            .noneMatch { it.contains("(?!") }
    }

    @Test
    fun `failed candidate branch emits a not-regex guard so a missing lastError can match`() {
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
