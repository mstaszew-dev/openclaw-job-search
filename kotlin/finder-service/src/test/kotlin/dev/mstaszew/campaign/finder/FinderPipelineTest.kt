package dev.mstaszew.campaign.finder

import dev.mstaszew.campaign.common.dedupe.DedupService
import dev.mstaszew.campaign.common.domain.TaskState
import dev.mstaszew.campaign.common.net.ChatTransport
import dev.mstaszew.campaign.common.net.LlmClient
import dev.mstaszew.campaign.common.repo.ApplyTaskRepository
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.SkipRepository
import dev.mstaszew.campaign.finder.collect.CollectedListing
import dev.mstaszew.campaign.finder.policy.EligibilityProperties
import dev.mstaszew.campaign.finder.score.CvScorer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class FinderPipelineTest {

    private val listings = mockk<JobListingRepository>()
    private val tasks = mockk<ApplyTaskRepository>()
    private val skips = mockk<SkipRepository> {
        every { save(any<dev.mstaszew.campaign.common.domain.SkipEntity>()) } answers { firstArg() }
    }
    private val events = mockk<EventRepository> {
        every { save(any<dev.mstaszew.campaign.common.domain.EventEntity>()) } answers { firstArg() }
    }
    private val tx = org.springframework.transaction.support.TransactionTemplate(
        mockk<org.springframework.transaction.PlatformTransactionManager> {
            every { getTransaction(any()) } returns org.springframework.transaction.support.SimpleTransactionStatus()
            every { commit(any()) } returns Unit
            every { rollback(any()) } returns Unit
        },
    )

    private val savedListingSlot = mutableListOf<dev.mstaszew.campaign.common.domain.JobListingEntity>()
    private val savedTaskSlot = mutableListOf<dev.mstaszew.campaign.common.domain.ApplyTaskEntity>()

    private fun scoringTransport(score: Int): ChatTransport = ChatTransport { _, _, _, _ ->
        """{"choices":[{"message":{"content":"{\"score\":$score,\"reason\":\"stack matches\"}"},"finish_reason":"stop"}]}"""
    }

    private fun pipeline(
        score: Int = 80,
        duplicate: Boolean = false,
        taskExists: Boolean = false,
        scorer: CvScorer? = null,
    ): FinderPipeline {
        every { listings.save(any()) } answers {
            val entity = arg<dev.mstaszew.campaign.common.domain.JobListingEntity>(0)
            entity.id = 1L
            savedListingSlot.add(entity)
            entity
        }
        every { tasks.save(any()) } answers {
            val task = arg<dev.mstaszew.campaign.common.domain.ApplyTaskEntity>(0)
            task.id = 9L
            savedTaskSlot.add(task)
            task
        }
        every { tasks.existsByListingId(any()) } returns taskExists
        every { listings.findBySourceAndSourceJobId(any(), any()) } returns null
        val dedup = mockk<DedupService>()
        every { dedup.check(any()) } returns
            if (duplicate) dev.mstaszew.campaign.common.dedupe.DuplicateDecision(true, dev.mstaszew.campaign.common.dedupe.MatchedOn.COMPANY, "nofluffjobs:1")
            else dev.mstaszew.campaign.common.dedupe.DuplicateDecision.CLEAR
        val effectiveScorer = scorer ?: CvScorer(
            LlmClient(
                baseUrl = "http://fake/v1",
                apiKey = null,
                model = "test",
                transport = scoringTransport(score),
            ),
        )
        return FinderPipeline(
            collector = { emptyList() },
            listings = listings,
            tasks = tasks,
            skips = skips,
            events = events,
            dedup = dedup,
            eligibility = dev.mstaszew.campaign.finder.policy.EligibilityPolicy(EligibilityProperties()),
            scorer = effectiveScorer,
            cvProfile = CvProfile(""),
            tx = tx,
            mapper = com.fasterxml.jackson.databind.ObjectMapper(),
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
        salaryMin = salaryMin?.let { java.math.BigDecimal(it) },
        stack = listOf("java", "spring"),
    )

    @Test
    fun `good candidate is enqueued with its score`() {
        val outcome = pipeline(score = 85).process(listing())

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Enqueued::class.java)
        assertThat((outcome as FinderPipeline.Outcome.Enqueued).score).isEqualTo(85)
        assertThat(savedTaskSlot.first().state).isEqualTo(TaskState.QUEUED)
        assertThat(savedTaskSlot.first().listingId).isEqualTo(1L)
        verify { events.save(any()) }
    }

    @Test
    fun `duplicate is never enqueued`() {
        val outcome = pipeline(duplicate = true).process(listing())

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Duplicate::class.java)
        assertThat(savedTaskSlot).isEmpty()
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `low score stops the enqueue`() {
        val outcome = pipeline(score = 30).process(listing())

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.LowScore::class.java)
        assertThat(savedTaskSlot).isEmpty()
    }

    @Test
    fun `below-floor salary is recorded as a salary skip`() {
        val outcome = pipeline().process(listing(salaryMin = "9000"))

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Ineligible::class.java)
        verify { skips.save(match { it.reason == dev.mstaszew.campaign.common.domain.SkipReason.SALARY }) }
        assertThat(savedTaskSlot).isEmpty()
    }

    @Test
    fun `lead title is recorded as a filter skip`() {
        val outcome = pipeline().process(listing(title = "Team Lead Java Developer"))

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Ineligible::class.java)
        verify { skips.save(match { it.reason == dev.mstaszew.campaign.common.domain.SkipReason.FILTER }) }
    }

    @Test
    fun `listing with an existing task is not re-enqueued`() {
        val outcome = pipeline(taskExists = true).process(listing())

        assertThat(outcome).isEqualTo(FinderPipeline.Outcome.AlreadyQueued)
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `gateway outage falls back to a passing score so the pipeline keeps flowing`() {
        val failing = mockk<ChatTransport>()
        every { failing.post(any(), any(), any(), any()) } throws
            dev.mstaszew.campaign.common.net.LlmClientException("gateway down", retryable = true)
        val failingScorer = CvScorer(
            LlmClient("http://fake/v1", null, "test", transport = failing, attempts = 1, sleeper = { }),
        )

        val outcome = pipeline(scorer = failingScorer).process(listing())

        assertThat(outcome).isInstanceOf(FinderPipeline.Outcome.Enqueued::class.java)
        assertThat((outcome as FinderPipeline.Outcome.Enqueued).score).isEqualTo(70)
        assertThat(savedTaskSlot.first().priority).isEqualTo(-10)
    }

    @Test
    fun `garbage llm output also falls back instead of aborting the listing`() {
        val garbage = ChatTransport { _, _, _, _ ->
            """{"choices":[{"message":{"content":"Sorry, I cannot help with that."}}]}"""
        }
        val garbageScorer = CvScorer(LlmClient("http://fake/v1", null, "test", transport = garbage, attempts = 1, sleeper = { }))

        assertThat(pipeline(scorer = garbageScorer).process(listing()))
            .isInstanceOf(FinderPipeline.Outcome.Enqueued::class.java)
    }
}
