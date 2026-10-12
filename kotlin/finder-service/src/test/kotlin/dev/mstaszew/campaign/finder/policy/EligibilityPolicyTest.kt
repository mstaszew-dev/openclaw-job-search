package dev.mstaszew.campaign.finder.policy

import dev.mstaszew.campaign.common.domain.JobListingEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EligibilityPolicyTest {

    private val policy = EligibilityPolicy(EligibilityProperties())

    private fun listing(
        title: String = "Java Developer",
        source: String = "nofluffjobs",
        region: String = "PL",
        remotePolicy: String? = "remote",
        stack: List<String> = listOf("java", "spring"),
        salaryMin: BigDecimal? = BigDecimal("18000"),
        salaryMax: BigDecimal? = BigDecimal("25000"),
    ) = JobListingEntity(
        source = source,
        sourceJobId = "x",
        company = "Acme",
        companyKey = "acme",
        roleTitle = title,
        url = "https://x.com/1",
        region = region,
        remotePolicy = remotePolicy,
        stack = stack,
        salaryMin = salaryMin,
        salaryMax = salaryMax,
    )

    @Test
    fun `eligible listing passes`() {
        assertThat(policy.check(listing()).eligible).isTrue()
    }

    @Test
    fun `no salary listed passes the floor`() {
        assertThat(policy.check(listing(salaryMin = null, salaryMax = null)).eligible).isTrue()
    }

    @Test
    fun `salary below floor fails only when listed`() {
        val verdict = policy.check(listing(salaryMin = BigDecimal("12000")))
        assertThat(verdict.eligible).isFalse()
        assertThat(verdict.reason).isEqualTo(IneligibilityReason.SALARY_BELOW_FLOOR)
    }

    @Test
    fun `max below floor with missing min fails`() {
        val verdict = policy.check(listing(salaryMin = null, salaryMax = BigDecimal("9000")))
        assertThat(verdict.reason).isEqualTo(IneligibilityReason.SALARY_BELOW_FLOOR)
    }

    @Test
    fun `hybrid or onsite fails`() {
        assertThat(policy.check(listing(remotePolicy = "hybrid")).reason)
            .isEqualTo(IneligibilityReason.NOT_REMOTE)
        assertThat(policy.check(listing(remotePolicy = null)).reason)
            .isEqualTo(IneligibilityReason.NOT_REMOTE)
    }

    @Test
    fun `polish remote tokens pass`() {
        assertThat(policy.check(listing(remotePolicy = "Zdalna")).eligible).isTrue()
        assertThat(policy.check(listing(remotePolicy = "praca zdalnie")).eligible).isTrue()
    }

    @Test
    fun `non-PL region fails`() {
        assertThat(policy.check(listing(region = "IL")).reason).isEqualTo(IneligibilityReason.REGION)
    }

    @Test
    fun `legacy sources fail`() {
        assertThat(policy.check(listing(source = "drushim")).reason).isEqualTo(IneligibilityReason.SOURCE)
    }

    @Test
    fun `seniority exclusions with word boundaries`() {
        assertThat(policy.check(listing(title = "Team Lead Java")).reason).isEqualTo(IneligibilityReason.EXCLUDED_TITLE)
        assertThat(policy.check(listing(title = "Head of Engineering")).reason).isEqualTo(IneligibilityReason.EXCLUDED_TITLE)
        assertThat(policy.check(listing(title = "Engineering Manager")).reason).isEqualTo(IneligibilityReason.EXCLUDED_TITLE)
        // boundary check: 'head' inside another word must NOT reject
        assertThat(policy.check(listing(title = "Backend Header Developer")).eligible).isTrue()
        assertThat(policy.check(listing(title = "Senior Java Developer")).eligible).isTrue()
    }

    @Test
    fun `tech exclusions anywhere in title or stack`() {
        assertThat(policy.check(listing(title = ".NET Developer")).reason).isEqualTo(IneligibilityReason.EXCLUDED_KEYWORD)
        assertThat(policy.check(listing(stack = listOf("java", "salesforce"))).reason)
            .isEqualTo(IneligibilityReason.EXCLUDED_KEYWORD)
        assertThat(policy.check(listing(title = "Data Engineer")).reason).isEqualTo(IneligibilityReason.EXCLUDED_KEYWORD)
    }

    @Test
    fun `stack allowlist gates odd listings`() {
        assertThat(policy.check(listing(title = "Consultant", stack = listOf("excel"))).reason)
            .isEqualTo(IneligibilityReason.STACK)
        assertThat(policy.check(listing(stack = listOf("kotlin"))).eligible).isTrue()
        assertThat(policy.check(listing(title = "PHP Developer", stack = listOf("laravel"))).eligible).isTrue()
        assertThat(policy.check(listing(title = "React Developer", stack = listOf("typescript"))).eligible).isTrue()
    }
}
