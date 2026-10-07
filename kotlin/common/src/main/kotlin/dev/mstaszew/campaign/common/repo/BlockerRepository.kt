package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.BlockerEntity
import org.springframework.data.jpa.repository.JpaRepository

interface BlockerRepository : JpaRepository<BlockerEntity, Long> {

    /** Repeat-block guard input: prior blocks for the same company. */
    fun countByCompanyKeyAndResolvedFalse(companyKey: String): Long

    fun countByResolvedFalse(): Long
}
