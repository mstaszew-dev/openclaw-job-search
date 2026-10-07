package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.JobListingEntity
import org.springframework.data.jpa.repository.JpaRepository

interface JobListingRepository : JpaRepository<JobListingEntity, Long> {
    fun findBySourceAndSourceJobId(source: String, sourceJobId: String): JobListingEntity?

    fun existsByCompanyKeyAndRoleTitle(companyKey: String, roleTitle: String): Boolean

    fun countBySource(source: String): Long
}
