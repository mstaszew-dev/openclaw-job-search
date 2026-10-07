package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.ApplicationEntity
import dev.mstaszew.campaign.common.domain.ApplicationStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface ApplicationRepository : JpaRepository<ApplicationEntity, String> {

    /** Dedup check 1 + 3: exact id or normalized URL match (any age). */
    fun findFirstByIdOrUrlOrderById(idValue: String, urlValue: String): ApplicationEntity?

    /**
     * Dedup check 2: company match. The caller must exclude non-company
     * sentinel keys (confidential, anonymous, ...) before consulting this.
     */
    fun findFirstByCompanyKeyOrderById(companyKey: String): ApplicationEntity?

    fun countByStatus(status: ApplicationStatus): Long

    fun countByAppliedAtAfter(after: Instant): Long

    fun existsByCompanyKeyAndRoleKey(companyKey: String, roleKey: String): Boolean
}
