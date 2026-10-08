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
import dev.mstaszew.campaign.common.repo.SkipRepository
import dev.mstaszew.campaign.finder.collect.CollectedListing
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
import java.util.concurrent.CompletableFuture

class FinderPipelineTest {

    private val listings = mockk<JobListingRepository>()
    private val jobState = mockk<JobStateRepository> {
        every { save(any<JobStateDocument>()) } answers { firstArg() }
        every { findById(any<String>()) } returns java.util.Optional.empty()
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

    private fun scoringTransport(score: Int): ChatTransport = ChatTransport { _, _, _, _ ->
        """{"choices":[{"message":{"content":"{\"score\":$score,\"reason\":\"stack matches\"}"},"finish_reason":"stop"}]}"""
    }

    private fun pipeline(
        score: Int = 80,
        duplicate: Boolean = false,
        scorer: CvScorer? = null,
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
            collector = { emptyList() },
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
        verify { jobState.save(match { it.id == "nofluffjobs:n-1" && it.status == JobStatus.PENDING }) }
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
}