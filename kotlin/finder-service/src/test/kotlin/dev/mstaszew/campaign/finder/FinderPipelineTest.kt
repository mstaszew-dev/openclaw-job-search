package dev.mstaszew.campaign.finder

import dev.mstaszew.campaign.common.dedupe.DedupService
import dev.mstaszew.campaign.common.dedupe.DuplicateDecision
import dev.mstaszew.campaign.common.dedupe.MatchedOn
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.JobStateDocument
import dev.mstaszew.campaign.common.domain.JobStatus
import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import dev.mstaszew.campaign.common.messaging.JobMessage
import dev.mstaszew.campaign.common.messaging.Topics
import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.net.LlmClientException
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.common.repo.ChromeLease
import dev.mstaszew.campaign.common.repo.SkipRepository
import dev.mstaszew.campaign.finder.collect.CollectedListing
import dev.mstaszew.campaign.finder.collect.ListingCollector
import dev.mstaszew.campaign.finder.policy.EligibilityPolicy
import dev.mstaszew.campaign.finder.policy.EligibilityProperties
import dev.mstaszew.campaign.finder.score.CvScorer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.CompletableFuture

class FinderPipelineTest {

    private val listings = mockk<JobListingRepository>()
    private val jobState = mockk<JobStateRepository> {
        every { save(any<JobStateDocument>()) } answers { firstArg() }
        every { findById(any<String>()) } returns java.util.Optional.empty()
        every { markReenqueued(any()) } returns Unit
    }
    private val skips = mockk<SkipRepository> {
        every { save(any<SkipEntity>()) } answers { firstArg() }
        every { existsByListingIdAndReason(any(), any()) } returns false
    }
    private val events = mockk<EventRepository> {
        every { save(any<EventEntity>()) } answers { firstArg() }
    }
    private val tx = org.springframework.transaction.support.TransactionTemplate(
        mockk<org.springframework.transaction.PlatformTransactionManager> {
            every { getTransaction(any()) } returns org.springframework.transaction.support.SimpleTransactionStatus()
            every { commit(any()) } returns Unit
            every { rollback(any()) } returns Unit
        },
    )

    private val producedSlot = slot<Any>()
    private val kafka = mockk<KafkaTemplate<String, Any>> {
        every {
            send(eq(Topics.JOBS), any<String>(), capture(producedSlot))
        } returns CompletableFuture.completedFuture(mockk<SendResult<String, Any>>())
    }

    private val leaseHandle = mockk<ChromeLease.Handle>(relaxUnitFun = true)

    /** Default stub: the lease is granted so pipeline tests reach the collector. */
    private val chromeLease = mockk<ChromeLease> {
        every { acquire(any(), any(), any(), any(), any(), any()) } returns leaseHandle
    }

    private fun scoringTransport(score: Int): ChatTransport = ChatTransport { _, _, _, _ ->
        """{"choices":[{"message":{"content":"{\"score\":$score,\"reason\":\"stack matches\"}"},"finish_reason":"stop"}]}"""
    }

    private fun pipeline(
        score: Int = 80,
        duplicate: Boolean = false,
        scorer: CvScorer? = null,
        collector: ListingCollector = ListingCollector { emptyList() },
    ): FinderPipeline {
        every { listings.save(any()) } answers {
            val entity = arg<JobListingEntity>(0)
            if (entity.id == null) entity.id = "65f0000000000000000000a1"
            entity
        }
        every { listings.findBySourceAndSourceJobId(any(), any()) } returns null
        val dedup = mockk<DedupService>()
        every { dedup.check(any()) } returns
            if (duplicate) DuplicateDecision(true, MatchedOn.COMPANY, "nofluffjobs:1")
            else DuplicateDecision.CLEAR
        val effectiveScorer = scorer ?: CvScorer(
            LlmClient(
                baseUrl = "http://fake/v1",
                apiKey = null,
                model = "test",
                transport = scoringTransport(score),
            ),
        )
        return FinderPipeline(
            collector = collector,
            listings = listings,
            jobState = jobState,
            skips = skips,
            events = events,
            dedup = dedup,
            eligibility = EligibilityPolicy(EligibilityProperties()),
            scorer = effectiveScorer,
            cvProfile = CvProfile(""),
            tx = tx,
            kafka = kafka,
            mapper = com.fasterxml.jackson.databind.ObjectMapper().registerModule(
                com.fasterxml.jackson.datatype.jsr310.JavaTimeModule(),
            ),
            props = FinderProperties(),
            chromeLease = chromeLease,
        )
    }

    private fun listing(
        title: String = "Senior Java Developer",
        salaryMin: String? = "18000",
    ) = CollectedListing(
        source = "nofluffjobs",
        sourceJobId = "n-1",
        company = "Funds-Tech Sp. z o.o.",
        roleTitle = title,
        url = "https://nofluffjobs.com/pl/job/x",
        remotePolicy = "remote",
        salaryMin = salaryMin?.let { BigDecimal(it) },
        stack = listOf("java", "spring"),
    )

    @Test
    fun `good candidate is produced to kafka and marked pending`() {
        val outcome = pipeline(score = 85).process(listing())

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Enqueued::class.java)
        assertThat((outcome as FinderPipeline.Outcome.Enqueued).score).isEqualTo(85)

        val message = producedSlot.captured as JobMessage
        assertThat(message.id).isEqualTo("nofluffjobs:n-1")
        assertThat(message.score).isEqualTo(85)
        assertThat(message.companyKey).isEqualTo("funds-tech")
        verify { jobState.markReenqueued(match { it.id == "nofluffjobs:n-1" && it.score == 85 }) }
        verify { events.save(match { it.action == "enqueued" }) }
    }

    /**
     * Keying by companyKey is what keeps every job for one company on one
     * partition, so one worker sees them in order.
     */
    @Test
    fun `the produced message is keyed by company key`() {
        pipeline().process(listing())
        verify { kafka.send(Topics.JOBS, "funds-tech", any<JobMessage>()) }
    }

    @Test
    fun `duplicate is never produced`() {
        val outcome = pipeline(duplicate = true).process(listing())

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Duplicate::class.java)
        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any<Any>()) }
        verify(exactly = 0) { events.save(any()) }
    }

    /**
     * One listing can surface on several filter pages in the same window, and
     * two Kafka records for one id each burn an apply attempt downstream
     * (observed live: attempts 5 against maxAttempts 3, 2026-10-20). Within
     * one discovery window a listing is handled exactly once.
     */
    @Test
    fun `a listing collected twice in one window is handled once`() {
        val pipe = pipeline()

        val first = pipe.process(listing())
        val second = pipe.process(listing())

        assertThat(first).isInstanceOf(FinderPipeline.Outcome.Enqueued::class.java)
        assertThat(second).isInstanceOf(FinderPipeline.Outcome.Duplicate::class.java)
        verify(exactly = 1) { kafka.send(any<String>(), any<String>(), any<Any>()) }
        // The second scan must not re-pay for scoring either.
        assertThat(second).extracting("matchedOn").isEqualTo("window-scan")
    }

    /**
     * Pins the seenThisWindow.clear() lifecycle: the within-window dedupe
     * must not survive across runs, or a listing whose first apply attempt
     * failed could never be re-enqueued and retried. Delete the clear() line
     * in run() and this test fails.
     */
    @Test
    fun `a second window can re-enqueue what the first window enqueued`() {
        val collector = ListingCollector { source ->
            if (source == "nofluffjobs") listOf(listing()) else emptyList()
        }
        val pipe = pipeline(collector = collector)

        pipe.run()
        pipe.run()

        verify(exactly = 2) { kafka.send(Topics.JOBS, "funds-tech", any<JobMessage>()) }
    }

    @Test
    fun `low score stops the enqueue`() {
        val outcome = pipeline(score = 30).process(listing())

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.LowScore::class.java)
        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any<Any>()) }
    }

    @Test
    fun `below-floor salary is recorded as a salary skip`() {
        val outcome = pipeline().process(listing(salaryMin = "9000"))

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Ineligible::class.java)
        verify { skips.save(match { it.reason == SkipReason.SALARY }) }
        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any<Any>()) }
    }

    @Test
    fun `lead title is recorded as a filter skip`() {
        val outcome = pipeline().process(listing(title = "Team Lead Java Developer"))

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Ineligible::class.java)
        verify { skips.save(match { it.reason == SkipReason.FILTER }) }
    }

    @Test
    fun `gateway outage falls back to a passing score so the pipeline keeps flowing`() {
        val failing = mockk<ChatTransport>()
        every { failing.post(any(), any(), any(), any()) } throws LlmClientException("gateway down", retryable = true)
        val failingScorer = CvScorer(
            LlmClient("http://fake/v1", null, "test", transport = failing, attempts = 1, sleeper = { }),
        )

        val outcome = pipeline(scorer = failingScorer).process(listing())

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Enqueued::class.java)
        assertThat((outcome as FinderPipeline.Outcome.Enqueued).score).isEqualTo(70)
        // The fallback score is still produced; the shadow run needs to see
        // exactly what the pipeline would have queued.
        assertThat((producedSlot.captured as JobMessage).score).isEqualTo(70)
    }

    @Test
    fun `garbage llm output also falls back instead of aborting the listing`() {
        val garbage = ChatTransport { _, _, _, _ ->
            """{"choices":[{"message":{"content":"Sorry, I cannot help with that."}}]}"""
        }
        val garbageScorer = CvScorer(
            LlmClient("http://fake/v1", null, "test", transport = garbage, attempts = 1, sleeper = { }),
        )

        assertThat(pipeline(scorer = garbageScorer).process(listing()))
            .isInstanceOf(FinderPipeline.Outcome.Enqueued::class.java)
    }

    /**
     * Mutual exclusion with the worker on the shared Chrome. A busy browser
     * defers the WHOLE run: no source is half-scanned while an apply is
     * mid-flight, and the next window picks the scan back up.
     */
    @Test
    fun `a busy browser defers the whole discovery run`() {
        val collectCalls = mutableListOf<String>()
        val collector = ListingCollector { source -> collectCalls.add(source); emptyList() }
        every { chromeLease.acquire(any(), any(), any(), any(), any(), any()) } returns null

        pipeline(collector = collector).run()

        assertThat(collectCalls).isEmpty()
    }

    @Test
    fun `discovery runs hold and release the browser lease`() {
        val collectCalls = mutableListOf<String>()
        val collector = ListingCollector { source -> collectCalls.add(source); emptyList() }

        pipeline(collector = collector).run()

        assertThat(collectCalls).containsExactly("nofluffjobs", "justjoin", "theprotocol", "pracuj")
        // The whole finder acquire contract, pinned: instance-unique owner,
        // discovery op, 30min ttl, no waiting (busy means skip the window),
        // default 2s poll, renewal every 5min so a long scan is never stolen
        // mid-run.
        verify {
            chromeLease.acquire(
                match { it.startsWith("finder-") },
                eq("discover"),
                eq(Duration.ofMinutes(30)),
                eq(Duration.ZERO),
                eq(Duration.ofSeconds(2)),
                eq(Duration.ofSeconds(300)),
            )
        }
        verify { leaseHandle.release() }
    }

    /**
     * Delivery before bookkeeping. A PENDING row whose Kafka send failed has
     * no recovery path: the worker never learns about the job, nothing walls
     * the listing, and once it leaves the feed the row sits forever (wielicka,
     * 2026-10-20: six failed sends, then five days stranded at PENDING
     * attempts=0). Send first; the worker's recordAttempt upsert seeds
     * job_state, so a message without a prior PENDING row is safe, and a
     * failed send retries naturally on the next window.
     */
    @Test
    fun `a failed kafka send leaves no pending row or enqueued event`() {
        every {
            kafka.send(eq(Topics.JOBS), any<String>(), any<Any>())
        } returns CompletableFuture.failedFuture(IllegalStateException("broker down"))

        val outcome = pipeline().process(listing())

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.SendFailed::class.java)
        verify(exactly = 0) { jobState.markReenqueued(any()) }
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `the pending row is written only after the send is acknowledged`() {
        pipeline().process(listing())

        io.mockk.verifyOrder {
            kafka.send(Topics.JOBS, "funds-tech", any<JobMessage>())
            jobState.markReenqueued(any())
        }
    }

    /**
     * Re-enqueue is one guarded server-side update (markReenqueued), not a
     * read-modify-save: the consume can win the race now that the send
     * precedes the write, and a save() replacement would clobber a terminal
     * status or reset attempts - the counter un-bounding the iteamly
     * re-grind loop read 1/3 across five grinds.
     */
    @Test
    fun `re-enqueue is one guarded upsert that preserves attempts`() {
        val outcome = pipeline().process(listing())

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Enqueued::class.java)
        verify(exactly = 1) { jobState.markReenqueued(match { it.id == "nofluffjobs:n-1" }) }
        verify(exactly = 0) { jobState.findById(any<String>()) }
        verify(exactly = 0) { jobState.save(any<JobStateDocument>()) }
    }
}