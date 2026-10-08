package dev.mstaszew.campaign.worker.apply

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import dev.mstaszew.campaign.common.domain.BlockerEntity
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import dev.mstaszew.campaign.common.repo.ApplicationRepository
import dev.mstaszew.campaign.common.repo.BlockerRepository
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.common.repo.SkipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class RecorderTest {

    private val applications = mockk<ApplicationRepository> {
        every { insertIfAbsent(any<ApplicationEntity>()) } returns true
    }
    private val jobState = mockk<JobStateRepository> {
        every { save(any<JobStateDocument>()) } answers { firstArg() }
        every { findById(any<String>()) } returns Optional.empty()
    }
    private val skips = mockk<SkipRepository> {
        every { save(any<SkipEntity>()) } answers { firstArg() }
        every { findFirstByReasonAndCompanyKeyAndBlockedRepeatTrue(any(), any()) } returns null
    }
    private val blockers = mockk<BlockerRepository> {
        every { save(any<BlockerEntity>()) } answers { firstArg() }
    }
    private val events = mockk<EventRepository> {
        every { save(any<EventEntity>()) } answers { firstArg() }
    }
    private val listings = mockk<JobListingRepository>()

    private val recorder = Recorder(
        applications,
        jobState,
        skips,
        blockers,
        events,
        listings,
        ObjectMapper().registerModule(JavaTimeModule()),
    )

    private val listing = JobListingEntity(
        id = "65f0000000000000000000a1",
        source = "justjoin",
        sourceJobId = "jj-1",
        company = "Acme",
        companyKey = "acme",
        roleTitle = "Java Dev",
        url = "https://x.com/1",
    )

    private val jobId = "justjoin:jj-1"

    @Test
    fun `valid confirmation records SUBMITTED`() {
        recorder.recordSubmitted(
            listing,
            jobId,
            Recorder.Evidence("https://ats.example.com/success", "Thank you", "greenhouse"),
        )

        verify { applications.insertIfAbsent(match { it.status == ApplicationStatus.SUBMITTED }) }
        verify { events.save(match { it.action == "submitted" }) }
        verify { jobState.save(match { it.id == jobId && it.status == JobStatus.SUBMITTED }) }
    }

    @Test
    fun `negative evidence records ATTEMPTED (not counted)`() {
        recorder.recordSubmitted(
            listing,
            jobId,
            Recorder.Evidence(null, "you have already applied for this role", null),
        )

        verify { applications.insertIfAbsent(match { it.status == ApplicationStatus.ATTEMPTED }) }
        verify { jobState.save(match { it.id == jobId && it.status == JobStatus.ATTEMPTED }) }
    }

    /**
     * The redelivery case, which is the whole reason insertIfAbsent exists
     * instead of save(). Kafka is at-least-once, so this will happen; the
     * ledger must not gain a second "submitted" event and job_state must not
     * be rewritten as if the apply just happened.
     */
    @Test
    fun `a redelivered submission writes nothing new`() {
        every { applications.insertIfAbsent(any<ApplicationEntity>()) } returns false

        val recorded = recorder.recordSubmitted(
            listing,
            jobId,
            Recorder.Evidence("https://ats.example.com/success", "Thank you", "greenhouse"),
        )

        assertThat(recorded).isFalse()
        verify(exactly = 0) { events.save(any()) }
        verify(exactly = 0) { jobState.save(any()) }
    }

    @Test
    fun `second block for one company writes the blockedRepeat skip`() {
        every { blockers.countByCompanyKeyAndResolvedFalse("acme") } returns 2

        recorder.recordBlocked(listing, jobId, "captcha", "recaptcha v2")

        verify { skips.save(match { it.blockedRepeat && it.blockCount == 2 && it.reason == SkipReason.DUPLICATE }) }
        verify { events.save(match { it.action == "blockedManual" }) }
    }

    @Test
    fun `first block does not write a repeat skip`() {
        every { blockers.countByCompanyKeyAndResolvedFalse("acme") } returns 1

        recorder.recordBlocked(listing, jobId, "captcha", "recaptcha v2")

        verify(exactly = 0) { skips.save(any()) }
    }

    @Test
    fun `an existing blockedRepeat row is updated in place rather than duplicated`() {
        every { blockers.countByCompanyKeyAndResolvedFalse("acme") } returns 5
        val existing = SkipEntity(reason = SkipReason.DUPLICATE, companyKey = "acme", blockedRepeat = true, blockCount = 2)
        every { skips.findFirstByReasonAndCompanyKeyAndBlockedRepeatTrue(SkipReason.DUPLICATE, "acme") } returns existing

        recorder.recordBlocked(listing, jobId, "captcha", "recaptcha v2")

        verify { skips.save(existing) }
        assertThat(existing.blockCount).isEqualTo(5)
    }

    @Test
    fun `duplicate skip is recorded and closes the job`() {
        recorder.recordSkippedDuplicate(listing, jobId, "company match")

        verify { skips.save(match { it.reason == SkipReason.DUPLICATE }) }
        verify { events.save(match { it.action == "skippedDuplicate" }) }
        verify { jobState.save(match { it.id == jobId && it.status == JobStatus.SKIPPED_DUPLICATE }) }
    }

    @Test
    fun `evidence is stored as a queryable subdocument`() {
        recorder.recordSubmitted(
            listing,
            jobId,
            Recorder.Evidence("https://ats.example.com/success", "Thank you", "greenhouse"),
        )

        val slot = io.mockk.slot<ApplicationEntity>()
        verify { applications.insertIfAbsent(capture(slot)) }
        assertThat(slot.captured.evidence?.path("type")?.asText()).isEqualTo("portal_confirmation")
        assertThat(slot.captured.evidence?.path("valid")?.asBoolean()).isTrue()
    }
}