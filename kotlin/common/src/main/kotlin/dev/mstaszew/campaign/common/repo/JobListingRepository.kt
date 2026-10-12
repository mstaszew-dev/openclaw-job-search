package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.JobListingEntity
import org.springframework.data.mongodb.repository.MongoRepository

interface JobListingRepository : MongoRepository<JobListingEntity, String> {
    fun findBySourceAndSourceJobId(source: String, sourceJobId: String): JobListingEntity?

    fun existsByCompanyKeyAndRoleTitle(companyKey: String, roleTitle: String): Boolean

    fun countBySource(source: String): Long
}