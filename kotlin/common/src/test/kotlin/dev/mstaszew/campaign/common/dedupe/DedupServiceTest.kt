package dev.mstaszew.campaign.common.dedupe

import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.SkipEntity
import dev.mstaszew.campaign.common.domain.SkipReason
import dev.mstaszew.campaign.common.repo.ApplicationRepository
import dev.mstaszew.campaign.common.repo.SkipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DedupServiceTest {

    private val applications = mockk<ApplicationRepository>()
    private val skips = mockk<SkipRepository>()
    private val service = DedupService(applications, skips)

    private fun app(id: String, companyKey: String? = null, url: String? = null) =
        ApplicationEntity(
            id = id,
            source = id.substringBefore(":"),
            sourceJobId = id.substringAfter(":"),
            company = "C",
            companyKey = companyKey ?: "key-$id",
            roleTitle = "Dev",
            url = url,
        )

    @Test
    fun `clear when nothing matches`() {
        val candidate = Candidate("nofluffjobs", "42", company = "New Co", companyKey = "new-co", url = "https://x.com/1")
        every { applications.findFirstByIdOrUrl("nofluffjobs:42", "https://x.com/1") } returns null
        every { applications.findFirstByCompanyKey("new-co") } returns null
        every { skips.findFirstByReasonAndCompanyKey(SkipReason.DUPLICATE, "new-co") } returns null
        every { skips.findFirstByReasonAndUrl(SkipReason.DUPLICATE, "https://x.com/1") } returns null
        every { skips.findFirstByReasonAndSourceAndSourceJobId(SkipReason.DUPLICATE, "nofluffjobs", "42") } returns null

        val decision = service.check(candidate)

        assertThat(decision.duplicate).isFalse()
        assertThat(decision.matchedOn).isNull()
    }

    @Test
    fun `matches application by id`() {
        every { applications.findFirstByIdOrUrl("nofluffjobs:42", any()) } returns app("nofluffjobs:42")

        val decision = service.check(Candidate("nofluffjobs", "42", "Some Co"))

        assertThat(decision.duplicate).isTrue()
        assertThat(decision.matchedOn).isEqualTo(MatchedOn.ID)
    }

    @Test
    fun `a candidate with no url never matches on the empty url`() {
        // UrlNormalizer normalizes a missing url to "", and "" is a stored
        // value: an application imported without a url. Matching on it would
        // report every later url-less job as a duplicate.
        every { applications.findFirstByIdOrUrl(any(), null) } returns null
        every { applications.findFirstByCompanyKey(any()) } returns null
        every { skips.findFirstByReasonAndCompanyKey(any(), any()) } returns null
        every { skips.findFirstByReasonAndUrl(any(), any()) } returns null
        every { skips.findFirstByReasonAndSourceAndSourceJobId(any(), any(), any()) } returns null

        val decision = service.check(Candidate("justjoin", "9", "Other Co", url = null))

        assertThat(decision.duplicate).isFalse()
        verify { applications.findFirstByIdOrUrl("justjoin:9", null) }
    }

    @Test
    fun `matches application by company key`() {
        every { applications.findFirstByIdOrUrl(any(), any()) } returns null
        every { applications.findFirstByCompanyKey("google") } returns app("justjoin:1", companyKey = "google")

        val decision = service.check(Candidate("justjoin", "9", "Google LLC", companyKey = "google"))

        assertThat(decision.duplicate).isTrue()
        assertThat(decision.matchedOn).isEqualTo(MatchedOn.COMPANY)
    }

    @Test
    fun `matches application by normalized url when ids differ`() {
        every { applications.findFirstByIdOrUrl(any(), "https://x.com/old") } returns
            app("nofluffjobs:5", url = "https://x.com/old")

        val decision = service.check(Candidate("justjoin", "9", "Other Co", url = "https://x.com/old"))

        assertThat(decision.duplicate).isTrue()
        assertThat(decision.matchedOn).isEqualTo(MatchedOn.URL)
    }

    @Test
    fun `matches duplicate skip by normalized url`() {
        every { applications.findFirstByIdOrUrl(any(), "https://x.com/s") } returns null
        every { applications.findFirstByCompanyKey(any()) } returns null
        every { skips.findFirstByReasonAndCompanyKey(SkipReason.DUPLICATE, any()) } returns null
        every { skips.findFirstByReasonAndUrl(SkipReason.DUPLICATE, "https://x.com/s") } returns
            SkipEntity(reason = SkipReason.DUPLICATE, url = "https://x.com/s", id = "11")

        val decision = service.check(Candidate("theprotocol", "3", "Url Co", url = "https://x.com/s"))

        assertThat(decision.duplicate).isTrue()
        assertThat(decision.matchedOn).isEqualTo(MatchedOn.URL)
    }

    @Test
    fun `matches duplicate skip by id`() {
        every { applications.findFirstByIdOrUrl(any(), any()) } returns null
        every { applications.findFirstByCompanyKey(any()) } returns null
        every { skips.findFirstByReasonAndCompanyKey(SkipReason.DUPLICATE, any()) } returns null
        every { skips.findFirstByReasonAndSourceAndSourceJobId(SkipReason.DUPLICATE, "justjoin", "9") } returns
            SkipEntity(reason = SkipReason.DUPLICATE, source = "justjoin", sourceJobId = "9", id = "12")

        val decision = service.check(Candidate("justjoin", "9", "Id Co"))

        assertThat(decision.duplicate).isTrue()
        assertThat(decision.matchedOn).isEqualTo(MatchedOn.ID)
    }

    @Test
    fun `sentinel company keys never match by company`() {
        every { applications.findFirstByIdOrUrl(any(), any()) } returns null
        every { skips.findFirstByReasonAndSourceAndSourceJobId(SkipReason.DUPLICATE, "justjoin", "9") } returns null

        val decision = service.check(Candidate("justjoin", "9", "Confidential", companyKey = "confidential"))

        assertThat(decision.duplicate).isFalse()
        verify(exactly = 0) { applications.findFirstByCompanyKey(any()) }
        verify(exactly = 0) { skips.findFirstByReasonAndCompanyKey(any(), any()) }
    }

    @Test
    fun `matches duplicate skip by company key (prior contact without application)`() {
        every { applications.findFirstByIdOrUrl(any(), any()) } returns null
        every { applications.findFirstByCompanyKey("acme") } returns null
        every { skips.findFirstByReasonAndCompanyKey(SkipReason.DUPLICATE, "acme") } returns SkipEntity(reason = SkipReason.DUPLICATE, companyKey = "acme", id = "7")

        val decision = service.check(Candidate("theprotocol", "5", "Acme Ltd", companyKey = "acme"))

        assertThat(decision.duplicate).isTrue()
        assertThat(decision.matchedOn).isEqualTo(MatchedOn.COMPANY)
    }

    @Test
    fun `computes company key when candidate has none`() {
        every { applications.findFirstByIdOrUrl(any(), any()) } returns null
        every { applications.findFirstByCompanyKey("funds-tech") } returns app("nofluffjobs:1", companyKey = "funds-tech")

        val decision = service.check(Candidate("nofluffjobs", "77", "Funds-Tech Sp. z o.o."))

        assertThat(decision.duplicate).isTrue()
        verify { applications.findFirstByCompanyKey("funds-tech") }
    }

    @Test
    fun `blank company key is never used for matching`() {
        every { applications.findFirstByIdOrUrl(any(), any()) } returns null
        every { skips.findFirstByReasonAndSourceAndSourceJobId(SkipReason.DUPLICATE, "x", "1") } returns null

        val decision = service.check(Candidate("x", "1", ""))

        assertThat(decision.duplicate).isFalse()
        verify(exactly = 0) { applications.findFirstByCompanyKey(any()) }
    }
}
