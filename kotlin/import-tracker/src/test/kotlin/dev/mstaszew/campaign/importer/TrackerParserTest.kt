package dev.mstaszew.campaign.importer

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import dev.mstaszew.campaign.common.domain.SkipReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TrackerParserTest {

    private val mapper = ObjectMapper()
    private val parser = TrackerParser(mapper)

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource(name)!!.readText()

    @Test
    fun `maps applications with canonical fields`() {
        val model = parser.parse(fixture("fixture-tracker.json"))

        assertThat(model.applications).hasSize(3)
        val first = model.applications[0]
        assertThat(first.id).isEqualTo("nofluffjobs:aaa-1")
        assertThat(first.companyKey).isEqualTo("funds-tech")
        assertThat(first.url).isEqualTo("https://nofluffjobs.com/pl/job/java-developer-funds-tech")
        assertThat(first.status).isEqualTo(ApplicationStatus.SUBMITTED)
        assertThat(first.salary?.path("min")?.asInt()).isEqualTo(15000)
        assertThat(first.stack).containsExactly("java", "spring")
        assertThat(first.appliedAt).isNotNull
        assertThat(first.evidence?.path("type")?.asText()).isEqualTo("portal_confirmation")
    }

    @Test
    fun `canonicalizes legacy slug ids to the source keyspace`() {
        val model = parser.parse(fixture("fixture-tracker.json"))

        val legacy = model.applications[2]
        assertThat(legacy.id).isEqualTo("nofluffjobs:nb-1")
        assertThat(legacy.source).isEqualTo("nofluffjobs")
        assertThat(legacy.sourceJobId).isEqualTo("nb-1")
    }

    @Test
    fun `keeps legacy id verbatim when source fields are absent`() {
        val model = parser.parse(
            """{"applications":[{"id":"old-slug","company":"Old Co","status":"submitted"}],
               "skipped":[],"blockers":[]}""",
        )

        val app = model.applications.single()
        assertThat(app.id).isEqualTo("old-slug")
        assertThat(app.source).isEqualTo("old-slug")
    }

    @Test
    fun `computes missing company key and normalizes url and stack string`() {
        val model = parser.parse(fixture("fixture-tracker.json"))

        val second = model.applications[1]
        assertThat(second.companyKey).isEqualTo("acme-new")
        assertThat(second.url).isEqualTo("https://justjoin.it/offers/kotlin-dev?jobid=ab12")
        assertThat(second.stack).containsExactly("kotlin")
        assertThat(second.status).isEqualTo(ApplicationStatus.ATTEMPTED)
        assertThat(second.salary?.asText()).isEqualTo("15000 - 18000 PLN B2B")
        assertThat(second.evidence?.asText()).isEqualTo("gmail_auto_reply")
    }

    @Test
    fun `drops legacy free-text skip reasons by contract`() {
        val model = parser.parse(fixture("fixture-tracker.json"))
        assertThat(model.skips).hasSize(3)
        assertThat(model.skips.map { it.companyKey }).doesNotContain("legacy-reason-co")
    }

    @Test
    fun `warns on unparsable appliedAt instead of dropping the row`() {
        val model = parser.parse(
            """{"applications":[{"id":"x:1","company":"C","appliedAt":"not-a-date","status":"submitted"}],
               "skipped":[],"blockers":[]}""",
        )

        assertThat(model.applications).hasSize(1)
        assertThat(model.applications[0].appliedAt).isNull()
        assertThat(model.warnings).anyMatch { it.contains("unparsable appliedAt") }
    }

    @Test
    fun `parses date-only appliedAt as UTC midnight`() {
        val model = parser.parse(
            """{"applications":[{"id":"x:1","company":"C","appliedAt":"2026-08-23","status":"submitted"}],
               "skipped":[],"blockers":[]}""",
        )

        assertThat(model.applications[0].appliedAt).isEqualTo(java.time.Instant.parse("2026-08-23T00:00:00Z"))
        assertThat(model.warnings).isEmpty()
    }

    @Test
    fun `parses zone-less appliedAt as UTC`() {
        val model = parser.parse(
            """{"applications":[
                 {"id":"x:1","company":"C","appliedAt":"2026-08-25T03:30:00","status":"submitted"},
                 {"id":"x:2","company":"C","appliedAt":"2026-07-29T17:43:04.077430","status":"submitted"}],
               "skipped":[],"blockers":[]}""",
        )

        assertThat(model.applications[0].appliedAt).isEqualTo(java.time.Instant.parse("2026-08-25T03:30:00Z"))
        assertThat(model.applications[1].appliedAt).isEqualTo(java.time.Instant.parse("2026-07-29T17:43:04.077430Z"))
        assertThat(model.warnings).isEmpty()
    }

    @Test
    fun `collapses duplicate application ids to the strongest variant`() {
        // Same id twice: a later ATTEMPTED re-apply must not beat the earlier
        // confirmed SUBMITTED, and two SUBMITTEDs collapse to the latest one.
        val model = parser.parse(
            """{"applications":[
                 {"id":"x:vistulo","company":"V","status":"submitted","appliedAt":"2026-06-17T10:36:02.422679+00:00"},
                 {"id":"x:vistulo","company":"V","status":"attempted","appliedAt":"2026-10-16T13:34:36.008410+00:00"},
                 {"id":"x:mindbox","company":"M","status":"submitted","appliedAt":"2026-06-17T12:20:11.457001+00:00"},
                 {"id":"x:mindbox","company":"M","status":"submitted","appliedAt":"2026-06-17T12:20:57.019593+00:00"}],
               "skipped":[],"blockers":[]}""",
        )

        assertThat(model.applications).hasSize(2)
        val vistulo = model.applications.first { it.id == "x:vistulo" }
        assertThat(vistulo.status).isEqualTo(ApplicationStatus.SUBMITTED)
        assertThat(vistulo.appliedAt).isEqualTo(java.time.Instant.parse("2026-06-17T10:36:02.422679Z"))
        val mindbox = model.applications.first { it.id == "x:mindbox" }
        assertThat(mindbox.appliedAt).isEqualTo(java.time.Instant.parse("2026-06-17T12:20:57.019593Z"))
    }

    @Test
    fun `maps attempted_no_confirmation to ATTEMPTED without a warning`() {
        val model = parser.parse(
            """{"applications":[{"id":"x:1","company":"C","status":"attempted_no_confirmation"}],
               "skipped":[],"blockers":[]}""",
        )

        assertThat(model.applications[0].status).isEqualTo(ApplicationStatus.ATTEMPTED)
        assertThat(model.warnings).isEmpty()
    }

    @Test
    fun `defaults a missing status to ATTEMPTED with a warning`() {
        val model = parser.parse(
            """{"applications":[{"id":"x:1","company":"C"}],
               "skipped":[],"blockers":[]}""",
        )

        assertThat(model.applications[0].status).isEqualTo(ApplicationStatus.ATTEMPTED)
        assertThat(model.warnings).anyMatch { it.contains("missing status") }
    }

    @Test
    fun `maps an unknown non-blank status to ATTEMPTED with a warning`() {
        val model = parser.parse(
            """{"applications":[{"id":"x:1","company":"C","status":"applied"}],
               "skipped":[],"blockers":[]}""",
        )

        assertThat(model.applications[0].status).isEqualTo(ApplicationStatus.ATTEMPTED)
        assertThat(model.warnings).anyMatch { it.contains("unknown status 'applied'") }
    }

    @Test
    fun `fails fast on skip rows without a parsable at`() {
        val broken = """{"applications":[],"skipped":[{"reason":"duplicate","company":"C"}],"blockers":[]}"""

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            parser.parse(broken)
        }
    }

    @Test
    fun `maps skips including blockedRepeat rows`() {
        val model = parser.parse(fixture("fixture-tracker.json"))

        assertThat(model.skips).hasSize(3)
        val dup = model.skips[0]
        assertThat(dup.reason).isEqualTo(SkipReason.DUPLICATE)
        assertThat(dup.companyKey).isEqualTo("prev-co")
        assertThat(model.skips[1].reason).isEqualTo(SkipReason.SALARY)
        val repeat = model.skips[2]
        assertThat(repeat.blockedRepeat).isTrue()
        assertThat(repeat.blockCount).isEqualTo(2)
    }

    @Test
    fun `normalizes missing skip company keys`() {
        val model = parser.parse(fixture("fixture-tracker.json"))
        assertThat(model.skips[1].companyKey).isEqualTo("poor-pay")
    }

    @Test
    fun `maps blockers`() {
        val model = parser.parse(fixture("fixture-tracker.json"))

        assertThat(model.blockers).hasSize(1)
        val blocker = model.blockers[0]
        assertThat(blocker.companyKey).isEqualTo("captcha-corp")
        assertThat(blocker.reason).isEqualTo("captcha")
        assertThat(blocker.resolved).isFalse()
    }

    @Test
    fun `captures stats for reconciliation`() {
        val model = parser.parse(fixture("fixture-tracker.json"))
        assertThat(model.stats.submitted).isEqualTo(3)
        assertThat(model.stats.skippedDuplicate).isEqualTo(2)
    }
}
