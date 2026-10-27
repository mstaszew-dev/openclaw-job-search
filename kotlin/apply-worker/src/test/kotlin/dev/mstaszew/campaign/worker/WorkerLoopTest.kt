package dev.mstaszew.campaign.worker

import dev.mstaszew.campaign.common.dedupe.DedupService
import dev.mstaszew.campaign.common.dedupe.DuplicateDecision
import dev.mstaszew.campaign.common.dedupe.MatchedOn
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import dev.mstaszew.campaign.common.messaging.DeadLetter
import dev.mstaszew.campaign.common.messaging.JobMessage
import dev.mstaszew.campaign.common.messaging.Topics
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.common.repo.ChromeLease
import dev.mstaszew.campaign.worker.apply.ApplyResult
import dev.mstaszew.campaign.worker.apply.BrowserAgent
import dev.mstaszew.campaign.worker.apply.Recorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import java.time.Duration
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
        every { markInFlight(any()) } returns Unit
    }
    private val dedup = mockk<DedupService>()
    private val recorder = mockk<Recorder>(relaxed = true)
    private val browserAgent = mockk<BrowserAgent>(relaxed = true)
    private val notifier = mockk<dev.mstaszew.campaign.worker.apply.SlackNotifier>(relaxUnitFun = true)
    private val leaseHandle = mockk<ChromeLease.Handle>(relaxUnitFun = true)

    /** Default stub: the lease is granted so live-mode tests reach the browser. */
    private val chromeLease = mockk<ChromeLease> {
        every { acquire(any(), any(), any(), any(), any(), any()) } returns leaseHandle
    }
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
        notifier = notifier,
        chromeLease = chromeLease,
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
     * job_state must agree with the applications row. The Recorder files
     * ATTEMPTED when the confirmation evidence does not verify, so writing an
     * unconditional SUBMITTED here left the ledger saying one thing and the
     * bookkeeping another.
     */
    @Test
    fun `live mode records ATTEMPTED when the confirmation does not verify`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Submitted(confirmationUrl = null, confirmationText = null, ats = null)

        loop("live").onMessage(job, ack)

        verify { jobState.save(match { it.id == "justjoin:jj-1" && it.status == JobStatus.ATTEMPTED }) }
        verify(exactly = 0) { jobState.save(match { it.status == JobStatus.SUBMITTED }) }
    }

    @Test
    fun `live mode records SUBMITTED when the confirmation verifies`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Submitted(
                confirmationUrl = "https://recruitee.com/accepted",
                confirmationText = null,
                ats = "recruitee",
            )

        loop("live").onMessage(job, ack)

        verify { jobState.save(match { it.id == "justjoin:jj-1" && it.status == JobStatus.SUBMITTED }) }
    }

    @Test
    fun `live mode posts a Slack submission when the confirmation verifies`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Submitted(
                confirmationUrl = "https://recruitee.com/accepted",
                confirmationText = null,
                ats = "recruitee",
            )

        loop("live").onMessage(job, ack)

        verify(exactly = 1) { notifier.submission("Acme", "Java Dev", "justjoin") }
    }

    @Test
    fun `live mode posts a Slack failure when the apply fails`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Failed("step cap 25 reached without outcome")

        loop("live").onMessage(job, ack)

        verify(exactly = 1) { notifier.failed("justjoin:jj-1", "step cap 25 reached without outcome") }
    }

    @Test
    fun `live mode posts a Slack block when the portal blocks the apply`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Blocked("captcha wall", "challenge at step 4")

        loop("live").onMessage(job, ack)

        verify(exactly = 1) { notifier.blocked("justjoin:jj-1", "captcha wall") }
    }

    @Test
    fun `live mode posts nothing to Slack for a duplicate`() {
        every { dedup.check(any()) } returns DuplicateDecision(duplicate = true)

        loop("live").onMessage(job, ack)

        verify(exactly = 0) { notifier.submission(any(), any(), any()) }
        verify(exactly = 0) { notifier.failed(any(), any()) }
        verify(exactly = 0) { notifier.blocked(any(), any()) }
        verify(exactly = 0) { notifier.deadLettered(any(), any()) }
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
            chromeLease = chromeLease,
            props = WorkerProperties(applyMode = "shadow", workerId = "w1", maxAttempts = 1),
            tx = tx,
            kafka = kafka,
            notifier = notifier,
            mapper = com.fasterxml.jackson.databind.ObjectMapper(),
        )

        loop.onMessage(job, ack)

        verify { kafka.send(dev.mstaszew.campaign.common.messaging.Topics.JOBS_DLQ, "acme", any<Any>()) }
        verify(exactly = 1) { notifier.deadLettered("justjoin:jj-1", "still broken") }
        assertThat(ack.count).isEqualTo(1)
        assertThat(stored.last().status).isEqualTo(JobStatus.DEAD)
    }

    /**
     * The dedup wall reads applications + DUPLICATE skips only, and the finder
     * enqueues anything the wall does not cover: a dead-lettered job used to
     * write neither, so one hostile listing was re-enqueued every dedup window
     * and re-ground the worker for hours (2026-10-25 zombie loop: 5
     * deliveries, three 80-step cap-outs, one iteamly page, browser wedged
     * after each). The dead-letter must wall the listing from re-enqueue,
     * strictly before the commit: a lost skip write here is a zombie that no
     * later cycle can ever wall.
     */
    @Test
    fun `a dead-lettered job writes the duplicate skip that walls re-enqueue`() {
        every { jobState.findById("justjoin:jj-1") } returns Optional.empty()
        every { dedup.check(any()) } throws IllegalStateException("still broken")
        every {
            kafka.send(dev.mstaszew.campaign.common.messaging.Topics.JOBS_DLQ, any<String>(), any<Any>())
        } returns CompletableFuture.completedFuture(mockk(relaxed = true))

        val loop = WorkerLoop(
            listings = listings,
            events = events,
            jobState = jobState,
            dedup = dedup,
            recorder = recorder,
            browserAgent = browserAgent,
            chromeLease = chromeLease,
            props = WorkerProperties(applyMode = "live", workerId = "w1", maxAttempts = 1),
            tx = tx,
            kafka = kafka,
            notifier = notifier,
            mapper = com.fasterxml.jackson.databind.ObjectMapper(),
        )

        loop.onMessage(job, ack)

        verify(exactly = 1) {
            recorder.recordDeadLettered(any(), eq("justjoin:jj-1"), match { it.startsWith("dead-letter") })
        }
        verifyOrder {
            recorder.recordDeadLettered(any(), any(), any())
            notifier.deadLettered(any(), any())
        }
        assertThat(ack.count).isEqualTo(1)
    }

    /**
     * The commit-after-write contract extends to the wall: a dead letter
     * whose skip write failed must not commit, or the listing stays a zombie
     * no later cycle can ever wall (the redelivery re-walls via overBound).
     */
    @Test
    fun `a failing dead-letter wall leaves the offset uncommitted`() {
        every { dedup.check(any()) } throws IllegalStateException("still broken")
        every { recorder.recordDeadLettered(any(), any(), any()) } throws IllegalStateException("mongo blip")
        every {
            kafka.send(dev.mstaszew.campaign.common.messaging.Topics.JOBS_DLQ, any<String>(), any<Any>())
        } returns CompletableFuture.completedFuture(mockk(relaxed = true))

        val loop = WorkerLoop(
            listings = listings,
            events = events,
            jobState = jobState,
            dedup = dedup,
            recorder = recorder,
            browserAgent = browserAgent,
            chromeLease = chromeLease,
            props = WorkerProperties(applyMode = "live", workerId = "w1", maxAttempts = 1),
            tx = tx,
            kafka = kafka,
            notifier = notifier,
            mapper = com.fasterxml.jackson.databind.ObjectMapper(),
        )

        loop.onMessage(job, ack)

        assertThat(ack.count).isZero()
    }

    /**
     * Browser mutual exclusion. The finder and this worker drive ONE shared
     * Chrome over CDP; live applies must hold the lease for the whole browser
     * loop and release it no matter how the loop ends.
     */
    @Test
    fun `live apply holds and releases the browser lease around the apply`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { chromeLease.acquire(any(), any(), any(), any(), any(), any()) } returns leaseHandle
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Failed("browser not reachable in test")

        loop("live").onMessage(job, ack)

        // Full acquire contract, pinned: instance-unique owner (never a bare
        // shared worker-id), apply op, the configured ttl/wait/renew trio and
        // the default 2s poll.
        verify {
            chromeLease.acquire(
                match { it.startsWith("worker:w1-") },
                eq("apply"),
                eq(Duration.ofMinutes(25)),
                eq(Duration.ofSeconds(600)),
                eq(Duration.ofSeconds(2)),
                eq(Duration.ofSeconds(300)),
            )
        }
        verify { leaseHandle.release() }
        assertThat(ack.count).isEqualTo(1)
    }

    @Test
    fun `live apply releases the lease even when the apply throws`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { chromeLease.acquire(any(), any(), any(), any(), any(), any()) } returns leaseHandle
        every { browserAgent.run(any(), any(), any(), any(), any()) } throws RuntimeException("boom")

        loop("live").onMessage(job, ack)

        verify { leaseHandle.release() }
        assertThat(ack.count).isEqualTo(0)
    }

    /**
     * The finder's scan beat us to the browser. Deferring beats failing: the
     * offset stays uncommitted, so the broker redelivers after the normal
     * backoff instead of this job being lost or dead-lettered.
     */
    @Test
    fun `a busy browser defers the job without committing or opening a tab`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { chromeLease.acquire(any(), any(), any(), any(), any(), any()) } returns null

        loop("live").onMessage(job, ack)

        verify(exactly = 0) { browserAgent.run(any(), any(), any(), any(), any()) }
        assertThat(ack.count).isEqualTo(0)
    }

    /**
     * The attempt counter is per LISTING id, but Kafka may carry several
     * records for one id (finder re-scans the same listing on two filter
     * pages). Past the bound, re-opening the browser is just more failures
     * for an id that already burned its shots: straight to the DLQ.
     */
    @Test
    fun `a delivery past the attempt bound dead-letters without opening the browser`() {
        every { jobState.beginAttempt(any()) } returns 4 // maxAttempts is 3
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Failed("browser must not open")
        val deadSlot = slot<DeadLetter>()
        every { kafka.send(Topics.JOBS_DLQ, "acme", capture(deadSlot)) } returns
            CompletableFuture.completedFuture(mockk(relaxed = true))

        loop("live").onMessage(job, ack)

        verify(exactly = 0) { browserAgent.run(any(), any(), any(), any(), any()) }
        verify { kafka.send(Topics.JOBS_DLQ, "acme", any<Any>()) }
        verify { jobState.save(match { it.id == "justjoin:jj-1" && it.status == JobStatus.DEAD }) }
        // The DLQ record is the operator's only triage artifact for this
        // incident class; it must carry the real bound and reason.
        assertThat(deadSlot.captured.attempts).isEqualTo(4)
        assertThat(deadSlot.captured.lastError).contains("attempt bound 3")
        assertThat(ack.count).isEqualTo(1)
    }

    /**
     * The attempt guard exists to keep the BROWSER closed for a listing that
     * already burned its shots. Shadow never opens the browser anyway, so an
     * over-bound delivery there takes the ordinary shadow path: no recorder
     * writes, no DLQ churn, just the release event.
     */
    @Test
    fun `shadow mode over-bound deliveries are simply shadow-released`() {
        every { jobState.beginAttempt(any()) } returns 4 // maxAttempts is 3
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR

        loop("shadow").onMessage(job, ack)

        verify(exactly = 0) { browserAgent.run(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { recorder.recordSkippedDuplicate(any(), any(), any()) }
        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any<Any>()) }
        verify { events.save(match { it.action == "shadow_released" }) }
        verify { jobState.save(match { it.id == "justjoin:jj-1" && it.status == JobStatus.SHADOW_RELEASED }) }
        assertThat(ack.count).isEqualTo(1)
    }

    /**
     * A crash between the portal submission and the offset commit redelivers
     * a job that is already in the applications ledger. Past the attempt
     * bound that redelivery must be classified SKIPPED_DUPLICATE - the dedup
     * read is a Mongo query, not a browser turn - because DEAD would hide a
     * real submission from the operator.
     */
    @Test
    fun `an over-bound duplicate redelivery is recorded as skipped, not dead`() {
        every { jobState.beginAttempt(any()) } returns 4 // maxAttempts is 3
        every { dedup.check(any()) } returns DuplicateDecision(true, MatchedOn.ID, "justjoin:jj-1")

        loop("live").onMessage(job, ack)

        verify(exactly = 0) { browserAgent.run(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any<Any>()) }
        verify { recorder.recordSkippedDuplicate(any(), "justjoin:jj-1", "dedupe:ID") }
        verify { jobState.save(match { it.id == "justjoin:jj-1" && it.status == JobStatus.SKIPPED_DUPLICATE }) }
        assertThat(ack.count).isEqualTo(1)
    }

    /**
     * A captcha block used to leave the listing un-walled, so the finder
     * re-enqueued it and the worker ground the SAME doomed form a second
     * time before the repeat-block company wall fired (margo 2026-10-26:
     * two full grinds into an unsolvable hCaptcha). The first captcha block
     * must wall the listing itself; the repeat-block guard stays for the
     * company level.
     */
    @Test
    fun `live mode walls a captcha-blocked listing on the first block`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Blocked("captcha", "hCaptcha on Lever form")

        loop("live").onMessage(job, ack)

        verify(exactly = 1) {
            recorder.recordCaptchaWall(any(), eq("justjoin:jj-1"), match { it.contains("captcha") })
        }
        verify { recorder.recordBlocked(any(), eq("justjoin:jj-1"), eq("captcha"), any()) }
        assertThat(ack.count).isEqualTo(1)
    }

    /** The grind already happened; a failed wall write must not cause a redelivery. */
    @Test
    fun `a failing captcha wall does not trigger a redelivery`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Blocked("captcha", "turnstile")
        every { recorder.recordCaptchaWall(any(), any(), any()) } throws IllegalStateException("mongo blip")

        loop("live").onMessage(job, ack)

        assertThat(ack.count).isEqualTo(1)
    }

    /**
     * Review finding 2026-10-26: portal-block is as permanent per listing as
     * captcha (no-SSO login wall, dead ATS form, paywall); the first block
     * walls the listing so it is never ground twice.
     */
    @Test
    fun `live mode walls a portal-blocked listing on the first block`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Blocked("portal-block", "login required, no Google SSO")

        loop("live").onMessage(job, ack)

        verify(exactly = 1) {
            recorder.recordPortalBlockWall(any(), eq("justjoin:jj-1"), match { it.contains("portal-block") })
        }
        verify(exactly = 0) { recorder.recordCaptchaWall(any(), any(), any()) }
        assertThat(ack.count).isEqualTo(1)
    }

    @Test
    fun `shadow mode never asks for the browser lease`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR

        loop().onMessage(job, ack)

        verify(exactly = 0) { chromeLease.acquire(any(), any(), any(), any(), any(), any()) }
        assertThat(ack.count).isEqualTo(1)
    }

    /**
     * A browser-failed apply is terminal in job_state but used to write
     * nothing the dedup wall reads, so the finder (which never reads
     * job_state) re-enqueued the listing on every window it stayed in the
     * feed, and the worker re-ground it - the FAILED twin of the dead-letter
     * zombie loop (iteamly, 2026-10-25: four grinds in one evening). The
     * Failed outcome must wall the listing like a dead letter does.
     */
    @Test
    fun `live mode walls a browser-failed listing from re-enqueue`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns
            ApplyResult.Failed("step cap 80 reached without outcome")

        loop("live").onMessage(job, ack)

        verify(exactly = 1) {
            recorder.recordFailedWall(any(), eq("justjoin:jj-1"), match { it.startsWith("browser-failed") })
        }
        verify { jobState.save(match { it.id == "justjoin:jj-1" && it.status == JobStatus.FAILED }) }
        verify(exactly = 1) { notifier.failed("justjoin:jj-1", "step cap 80 reached without outcome") }
        assertThat(ack.count).isEqualTo(1)
    }

    /**
     * The grind already happened; a wall-write failure must degrade loudly to
     * the pre-wall behavior rather than throw the message back into
     * redelivery, which would re-open the browser for a listing that just
     * failed its grind.
     */
    @Test
    fun `a failing failed-wall does not trigger a redelivery`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns ApplyResult.Failed("womp")
        every { recorder.recordFailedWall(any(), any(), any()) } throws IllegalStateException("mongo blip")

        loop("live").onMessage(job, ack)

        assertThat(ack.count).isEqualTo(1)
        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any<Any>()) }
    }

    /**
     * The 2026-10-27 wedge burn: a wedged browser (initializeServer
     * timeouts) terminally failed unit after unit, and each failedWall
     * burned a good listing - seven pracuj jobs walled in ~20 minutes for
     * an infrastructure failure. Infrastructure signatures must NOT wall.
     */
    @Test
    fun `infrastructure failures do not wall the listing`() {
        every { dedup.check(any()) } returns DuplicateDecision.CLEAR
        every { browserAgent.run(any(), any(), any(), any(), any()) } returns ApplyResult.Failed(
            "loop breaker: browser_navigate failed 5 consecutive times; last error: " +
                "tool browser_navigate failed: TimeoutError: async initializeServer: Timeout 30000ms exceeded",
        )

        loop("live").onMessage(job, ack)

        verify(exactly = 0) { recorder.recordFailedWall(any(), any(), any()) }
        assertThat(ack.count).isEqualTo(1)
    }
}