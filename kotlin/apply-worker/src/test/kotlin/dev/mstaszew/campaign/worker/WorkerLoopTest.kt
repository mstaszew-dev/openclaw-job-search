package dev.mstaszew.campaign.worker

import dev.mstaszew.campaign.common.dedupe.DedupService
import dev.mstaszew.campaign.common.dedupe.DuplicateDecision
import dev.mstaszew.campaign.common.dedupe.MatchedOn
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import dev.mstaszew.campaign.common.messaging.JobMessage
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.worker.apply.ApplyResult
import dev.mstaszew.campaign.worker.apply.BrowserAgent
import dev.mstaszew.campaign.worker.apply.Recorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.Optional

class WorkerLoopTest {

    private val listings = mockk<JobListingRepository> {
        every { findBySourceAndSourceJobId(any(), any()) } returns
            JobListingEntity(
                id = "65f0000000000000000000a1",
                source = "justjoin",
                sourceJobId = "jj-1",
                company = "Acme",
                companyKey = "acme",
                roleTitle = "Java Dev",
                url = "https://x.com/1",
            )
    }
    private val events = mockk<EventRepository> {
        every { save(any<EventEntity>()) } answers { firstArg() }
    }
    private val jobState = mockk<JobStateRepository> {
        every { save(any<JobStateDocument>()) } answers { firstArg() }
        every { findById(any<String>()) } returns Optional.empty()
        every { beginAttempt(any()) } returns 1
    }
    private val dedup = mockk<DedupService>()
    private val recorder = mockk<Recorder>(relaxed = true)
    private val browserAgent = mockk<BrowserAgent>(relaxed = true)
    private val kafka = mockk<KafkaTemplate<String, Any>>(relaxed = true)

    /**
     * Counts commits instead of mocking the interface, because "was this
     * offset committed?" is the whole contract under test and a mockk verify
     * would not survive being read next to the assertion.
     */
    private class CountingAck : Acknowledgment {
        var count = 0
            private set

        override fun acknowledge() {
            count++
        }
    }

    private val ack = CountingAck()

    private val tx = org.springframework.transaction.support.TransactionTemplate(
        mockk<org.springframework.transaction.PlatformTransactionManager> {
            every { getTransaction(any()) } returns org.springframework.transaction.support.SimpleTransactionStatus()
            every { commit(any()) } returns Unit
            every { rollback(any()) } returns Unit
        },
    )

    private val job = JobMessage(
        source = "justjoin",
        sourceJobId = "jj-1",
        company = "Acme",
        companyKey = "acme",
        roleTitle = "Java Dev",
        url = "https://x.com/1",
        region = "PL",
        score = 85,
        scoreReason = "stack matches",
        enqueuedAt = Instant.parse("2026-03-01T09:00:00Z"),
    )

    private fun loop(applyMode: String = "shadow") = WorkerLoop(
        listings = listings,
        events = events,
        jobState = jobState,
        dedup = dedup,
        recorder = recorder,
        browserAgent = browserAgent,
        props = WorkerProperties(applyMode = applyMode, workerId = "w1"),
        tx = tx,
        kafka = kafka,
        mapper = com.fasterxml.jackson.databind.ObjectMapper(),
    )

    @Test
    fun `shadow mode releases the job, records the event and commits`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR

        loop().onMessage(job, ack)

        assertThat(ack.count).isEqualTo(1)
        verify { events.save(match { it.action == "shadow_released" }) }
        verify { jobState.save(match { it.id == "justjoin:jj-1" && it.status == JobStatus.SHADOW_RELEASED }) }
        verify(exactly = 0) { browserAgent.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `shadow mode notes a duplicate in the ledger`() {
        every { dedup.check(any()) } returns DuplicateDecision(true, MatchedOn.COMPANY, "justjoin:old")

        loop().onMessage(job, ack)

        verify {
            events.save(
                match {
                    it.action == "shadow_released" &&
                        it.record.path("duplicate").asBoolean() &&
                        it.record.path("matchedOn").asText() == "COMPANY"
                },
            )
        }
    }

    @Test
    fun `live mode dispatches to the browser agent`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Failed("browser not reachable in test")

        loop("live").onMessage(job, ack)

        verify { browserAgent.run(eq("justjoin:jj-1"), eq("65f0000000000000000000a1"), any(), any(), any()) }
        assertThat(ack.count).isEqualTo(1)
    }

    /**
     * The pre-action guard. insertIfAbsent protects the applications row, but
     * that row is written after the form has already reached the employer, so
     * the dedupe re-check is the only thing standing between a redelivery and
     * a second real application.
     */
    @Test
    fun `live mode does not open the browser for a duplicate`() {
        every { dedup.check(any()) } returns DuplicateDecision(true, MatchedOn.COMPANY, "justjoin:old")

        loop("live").onMessage(job, ack)

        verify(exactly = 0) { browserAgent.run(any(), any(), any(), any(), any()) }
        verify { recorder.recordSkippedDuplicate(any(), any(), match { it.startsWith("dedupe:") }) }
        assertThat(ack.count).isEqualTo(1)
    }

    /**
     * The commit-after-write contract. Auto-commit would lose the job here; a
     * failure below the bound must leave the offset uncommitted so the broker
     * redelivers, and must NOT dead-letter a message that is still retryable.
     */
    @Test
    fun `a retryable failure does not commit and does not dead-letter`() {
        every { dedup.check(any()) } throws IllegalStateException("mongo blip")

        loop().onMessage(job, ack)

        assertThat(ack.count).isZero()
        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any<Any>()) }
    }

    @Test
    fun `an exhausted job is dead-lettered and then committed`() {
        val stored = mutableListOf<JobStateDocument>()
        every { jobState.findById("justjoin:jj-1") } answers { Optional.ofNullable(stored.lastOrNull()) }
        every { jobState.save(any<JobStateDocument>()) } answers {
            val doc = firstArg<JobStateDocument>()
            stored.add(doc)
            doc
        }
        every { dedup.check(any()) } throws IllegalStateException("still broken")
        // The DLQ send is awaited before the commit, so it must complete.
        every {
            kafka.send(dev.mstaszew.campaign.common.messaging.Topics.JOBS_DLQ, any<String>(), any<Any>())
        } returns CompletableFuture.completedFuture(mockk(relaxed = true))

        // maxAttempts = 1 so the first failure is already the last attempt.
        val loop = WorkerLoop(
            listings = listings,
            events = events,
            jobState = jobState,
            dedup = dedup,
            recorder = recorder,
            browserAgent = browserAgent,
            props = WorkerProperties(applyMode = "shadow", workerId = "w1", maxAttempts = 1),
            tx = tx,
            kafka = kafka,
            mapper = com.fasterxml.jackson.databind.ObjectMapper(),
        )

        loop.onMessage(job, ack)

        verify { kafka.send(dev.mstaszew.campaign.common.messaging.Topics.JOBS_DLQ, "acme", any<Any>()) }
        assertThat(ack.count).isEqualTo(1)
        assertThat(stored.last().status).isEqualTo(JobStatus.DEAD)
    }
}