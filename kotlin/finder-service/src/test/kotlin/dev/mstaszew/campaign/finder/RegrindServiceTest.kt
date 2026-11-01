package dev.mstaszew.campaign.finder

import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.messaging.Topics
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.common.repo.RegrindCandidate
import dev.mstaszew.campaign.common.messaging.JobMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.concurrent.CompletableFuture

class RegrindServiceTest {

    private val jobState = mockk<JobStateRepository>(relaxUnitFun = true)
    private val listings = mockk<JobListingRepository>()
    private val produced = slot<Any>()
    private val kafka = mockk<KafkaTemplate<String, Any>> {
        every { send(eq(Topics.JOBS), any<String>(), capture(produced)) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, Any>>())
    }

    private fun props(
        enabled: Boolean = true,
        batch: Int = 4,
        cooldownHours: Long = 12,
    ) = FinderProperties(
        regrindEnabled = enabled,
        regrindBatchPerWindow = batch,
        regrindCooldownHours = cooldownHours,
    )

    private fun candidate(
        id: String = "justjoin:consid-lead-java-developer",
        source: String = "justjoin",
        sourceJobId: String = "consid-lead-java-developer",
        companyKey: String = "consid",
        score: Int? = 72,
        attempts: Int = 1,
        maxAttempts: Int = 3,
    ) = RegrindCandidate(
        id = id,
        source = source,
        sourceJobId = sourceJobId,
        companyKey = companyKey,
        score = score,
        attempts = attempts,
        maxAttempts = maxAttempts,
    )

    private fun listing(
        source: String = "justjoin",
        sourceJobId: String = "consid-lead-java-developer",
        company: String = "Consid",
    ) = JobListingEntity(
        source = source,
        sourceJobId = sourceJobId,
        company = company,
        companyKey = "consid",
        roleTitle = "Lead Java Developer",
        url = "https://justjoin.it/job-offer/consid-lead-java-developer",
        region = "PL",
    )

    @Test
    fun `disabled property skips the pass entirely`() {
        val service = RegrindService(jobState, listings, kafka, props(enabled = false))

        service.run()

        verify(exactly = 0) { jobState.findRegrindCandidates(any(), any(), any()) }
        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any()) }
    }

    @Test
    fun `regrinds a failed candidate by claiming it and producing a message built from state and listing`() {
        val batch = listOf(
            candidate(score = 72, attempts = 1),
            candidate(
                id = "nofluffjobs:e2e-monitor",
                source = "nofluffjobs",
                sourceJobId = "e2e-monitor",
                companyKey = "e2e-monitor",
                score = null,
                attempts = 2,
            ),
        )
        every { jobState.findRegrindCandidates(any(), any(), any()) } returns batch
        every { listings.findBySourceAndSourceJobId(any(), any()) } returnsMany
            listOf(listing(), listing(source = "nofluffjobs", sourceJobId = "e2e-monitor", company = "E2E"))
        every { jobState.regrindClaim(any(), any()) } returns true

        RegrindService(jobState, listings, kafka, props()).run()

        verify(exactly = 2) { jobState.regrindClaim(any(), any()) }
        verify(exactly = 2) { kafka.send(eq(Topics.JOBS), any<String>(), any()) }
        val message = produced.captured as JobMessage
        assertThat(message.source).isEqualTo("nofluffjobs")
        assertThat(message.companyKey).isEqualTo("e2e-monitor")
        // listing join supplies the presentation fields the Kafka payload needs
        assertThat(message.company).isEqualTo("E2E")
        assertThat(message.roleTitle).isEqualTo("Lead Java Developer")
        assertThat(message.url).isEqualTo("https://justjoin.it/job-offer/consid-lead-java-developer")
        assertThat(message.region).isEqualTo("PL")
        // state with no score falls back to the neutral operator score
        assertThat(message.score).isEqualTo(70)
        assertThat(message.scoreReason).contains("finder-regrind")
        assertThat(message.id).isEqualTo("nofluffjobs:e2e-monitor")
    }

    @Test
    fun `uses the stored state score when present`() {
        every { jobState.findRegrindCandidates(any(), any(), any()) } returns listOf(candidate(score = 88))
        every { listings.findBySourceAndSourceJobId(any(), any()) } returns listing()
        every { jobState.regrindClaim(any(), any()) } returns true

        RegrindService(jobState, listings, kafka, props()).run()

        val message = produced.captured as JobMessage
        assertThat(message.score).isEqualTo(88)
    }

    @Test
    fun `candidate without a stored listing is never claimed or produced`() {
        every { jobState.findRegrindCandidates(any(), any(), any()) } returns listOf(candidate())
        every { listings.findBySourceAndSourceJobId(any(), any()) } returns null

        RegrindService(jobState, listings, kafka, props()).run()

        verify(exactly = 0) { jobState.regrindClaim(any(), any()) }
        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any()) }
    }

    @Test
    fun `claim lost to another pass does not produce a message`() {
        every { jobState.findRegrindCandidates(any(), any(), any()) } returns listOf(candidate())
        every { listings.findBySourceAndSourceJobId(any(), any()) } returns listing()
        every { jobState.regrindClaim(any(), any()) } returns false

        RegrindService(jobState, listings, kafka, props()).run()

        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any()) }
    }

    @Test
    fun `kafka send failure reverts the claim so the row stays FAILED for the next window`() {
        every { jobState.findRegrindCandidates(any(), any(), any()) } returns listOf(candidate())
        every { listings.findBySourceAndSourceJobId(any(), any()) } returns listing()
        every { jobState.regrindClaim(any(), any()) } returns true
        every {
            kafka.send(eq(Topics.JOBS), any<String>(), any())
        } returns CompletableFuture.failedFuture(RuntimeException("broker down"))

        RegrindService(jobState, listings, kafka, props()).run()

        verify(exactly = 1) { jobState.regrindRevert("justjoin:consid-lead-java-developer") }
    }

    @Test
    fun `a claimed send failure for one job does not stop the remaining batch`() {
        every { jobState.findRegrindCandidates(any(), any(), any()) } returns listOf(
            candidate(),
            candidate(
                id = "nofluffjobs:e2e-monitor",
                source = "nofluffjobs",
                sourceJobId = "e2e-monitor",
                companyKey = "e2e-monitor",
            ),
        )
        every { listings.findBySourceAndSourceJobId(any(), any()) } returns listing()
        every { jobState.regrindClaim(any(), any()) } returns true
        every {
            kafka.send(eq(Topics.JOBS), eq("consid"), any())
        } returns CompletableFuture.failedFuture(RuntimeException("broker down"))

        RegrindService(jobState, listings, kafka, props()).run()

        verify(exactly = 1) { jobState.regrindRevert("justjoin:consid-lead-java-developer") }
        verify(exactly = 1) { kafka.send(eq(Topics.JOBS), eq("e2e-monitor"), any()) }
        verify(exactly = 0) { jobState.regrindRevert("nofluffjobs:e2e-monitor") }
    }
}
