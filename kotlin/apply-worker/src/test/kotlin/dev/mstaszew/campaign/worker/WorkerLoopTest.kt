package dev.mstaszew.campaign.worker

import dev.mstaszew.campaign.common.dedupe.DedupService
import dev.mstaszew.campaign.common.dedupe.DuplicateDecision
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.TaskState
import dev.mstaszew.campaign.common.repo.ApplyTaskRepository
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.worker.apply.BrowserAgent
import dev.mstaszew.campaign.worker.apply.Recorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class WorkerLoopTest {

    private val tasks = mockk<ApplyTaskRepository>()
    private val listings = mockk<JobListingRepository>()
    private val events = mockk<EventRepository> {
        every { save(any<dev.mstaszew.campaign.common.domain.EventEntity>()) } answers { firstArg() }
    }
    private val dedup = mockk<DedupService>()
    private val recorder = mockk<Recorder>(relaxed = true)
    private val browserAgent = mockk<BrowserAgent>(relaxed = true)
    private val props = WorkerProperties(applyMode = "shadow", workerId = "w1")

    private val tx = org.springframework.transaction.support.TransactionTemplate(
        mockk<org.springframework.transaction.PlatformTransactionManager> {
            every { getTransaction(any()) } returns org.springframework.transaction.support.SimpleTransactionStatus()
            every { commit(any()) } returns Unit
            every { rollback(any()) } returns Unit
        },
    )
    private val loop = WorkerLoop(tasks, listings, events, dedup, recorder, browserAgent, props, tx, com.fasterxml.jackson.databind.ObjectMapper())

    private val listing = JobListingEntity(
        id = 1,
        source = "justjoin",
        sourceJobId = "jj-1",
        company = "Acme",
        companyKey = "acme",
        roleTitle = "Java Dev",
        url = "https://x.com/1",
    )

    private fun claimedTask() = dev.mstaszew.campaign.common.domain.ApplyTaskEntity(
        id = 9,
        listingId = 1,
        state = TaskState.CLAIMED,
    )

    @Test
    fun `shadow tick releases the task and records the event`() {
        val task = claimedTask()
        every { tasks.claim("w1", 600) } returns task
        every { listings.findById(1) } returns Optional.of(listing)
        every { tasks.findById(9) } returns Optional.of(task)
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR

        loop.tick()

        assertThat(task.state).isEqualTo(TaskState.SHADOW_RELEASED)
        verify {
            events.save(match { it.action == "shadow_released" })
        }
        verify(exactly = 0) { browserAgent.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `empty queue does nothing`() {
        every { tasks.claim(any(), any()) } returns null
        loop.tick()
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `live mode dispatches to the browser agent`() {
        val liveProps = WorkerProperties(applyMode = "live", workerId = "w1")
        val liveLoop = WorkerLoop(tasks, listings, events, dedup, recorder, browserAgent, liveProps, tx, com.fasterxml.jackson.databind.ObjectMapper())
        val task = claimedTask()
        every { tasks.claim("w1", 600) } returns task
        every { listings.findById(1) } returns Optional.of(listing)
        every {
            browserAgent.run(any(), any(), any(), any(), any())
        } returns dev.mstaszew.campaign.worker.apply.ApplyResult.Failed("browser not reachable in test")
        every { tasks.findById(9) } returns Optional.of(task)

        liveLoop.tick()

        verify { browserAgent.run(any(), any(), any(), any(), any()) }
    }
}
