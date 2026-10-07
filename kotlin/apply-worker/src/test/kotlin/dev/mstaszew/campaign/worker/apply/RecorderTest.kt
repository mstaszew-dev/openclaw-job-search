package dev.mstaszew.campaign.worker.apply

import dev.mstaszew.campaign.common.domain.ApplicationStatus
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import dev.mstaszew.campaign.common.domain.TaskState
import dev.mstaszew.campaign.common.repo.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class RecorderTest {

    private val applications = mockk<ApplicationRepository> {
        every { save(any<dev.mstaszew.campaign.common.domain.ApplicationEntity>()) } answers { firstArg() }
    }
    private val tasks = mockk<ApplyTaskRepository> {
        every { save(any<dev.mstaszew.campaign.common.domain.ApplyTaskEntity>()) } answers { firstArg() }
    }
    private val skips = mockk<SkipRepository> {
        every { save(any<dev.mstaszew.campaign.common.domain.SkipEntity>()) } answers { firstArg() }
        every { findFirstByReasonAndCompanyKeyAndBlockedRepeatTrue(any(), any()) } returns null
    }
    private val blockers = mockk<BlockerRepository>()
    private val events = mockk<EventRepository> {
        every { save(any<dev.mstaszew.campaign.common.domain.EventEntity>()) } answers { firstArg() }
    }
    private val listings = mockk<JobListingRepository>()

    private val recorder = Recorder(applications, tasks, skips, blockers, events, listings, com.fasterxml.jackson.databind.ObjectMapper(), "w1")

    private val listing = JobListingEntity(
        id = 1,
        source = "justjoin",
        sourceJobId = "jj-1",
        company = "Acme",
        companyKey = "acme",
        roleTitle = "Java Dev",
        url = "https://x.com/1",
    )

    private fun task(state: TaskState = TaskState.CLAIMED, attempts: Int = 1) =
        dev.mstaszew.campaign.common.domain.ApplyTaskEntity(
            id = 9,
            listingId = 1,
            state = state,
            attempts = attempts,
            claimedBy = "w1",
            claimExpiresAt = java.time.Instant.now().plusSeconds(300),
        ).also { every { tasks.findById(9) } returns Optional.of(it) }

    @Test
    fun `valid confirmation records SUBMITTED`() {
        val t = task()
        recorder.recordSubmitted(listing, 9, Recorder.Evidence("https://ats.example.com/success", "Thank you", "greenhouse"))

        verify { applications.save(match { it.status == ApplicationStatus.SUBMITTED }) }
        verify { tasks.save(match { it.state == TaskState.SUBMITTED }) }
        assertThat(t.state).isEqualTo(TaskState.SUBMITTED)
        verify { events.save(match { it.action == "submitted" }) }
    }

    @Test
    fun `negative evidence records ATTEMPTED (not counted)`() {
        val t = task()
        recorder.recordSubmitted(listing, 9, Recorder.Evidence(null, "you have already applied for this role", null))

        verify { applications.save(match { it.status == ApplicationStatus.ATTEMPTED }) }
        verify { tasks.save(match { it.state == TaskState.ATTEMPTED }) }
        assertThat(t.state).isEqualTo(TaskState.ATTEMPTED)
    }

    @Test
    fun `second block for one company writes the blockedRepeat skip`() {
        val t = task()
        every { blockers.save(any<dev.mstaszew.campaign.common.domain.BlockerEntity>()) } answers { firstArg() }
        every { blockers.countByCompanyKeyAndResolvedFalse("acme") } returns 2

        recorder.recordBlocked(listing, 9, "captcha", "recaptcha v2")

        verify { skips.save(match { it.blockedRepeat && it.blockCount == 2 && it.reason == SkipReason.DUPLICATE }) }
        assertThat(t.state).isEqualTo(TaskState.BLOCKED)
    }

    @Test
    fun `first block does not write a repeat skip`() {
        val t = task()
        every { blockers.save(any<dev.mstaszew.campaign.common.domain.BlockerEntity>()) } answers { firstArg() }
        every { blockers.countByCompanyKeyAndResolvedFalse("acme") } returns 1

        recorder.recordBlocked(listing, 9, "captcha", "recaptcha v2")

        verify(exactly = 0) { skips.save(any()) }
        assertThat(t.state).isEqualTo(TaskState.BLOCKED)
    }

    @Test
    fun `duplicate skip records and closes the task`() {
        val t = task()
        recorder.recordSkippedDuplicate(listing, 9, "company match")

        verify { skips.save(match { it.reason == SkipReason.DUPLICATE }) }
        assertThat(t.state).isEqualTo(TaskState.SKIPPED_DUPLICATE)
    }
}
