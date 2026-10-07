package dev.mstaszew.campaign.api

import dev.mstaszew.campaign.common.domain.ApplicationStatus
import dev.mstaszew.campaign.common.domain.SkipReason
import dev.mstaszew.campaign.common.domain.TaskState
import dev.mstaszew.campaign.common.repo.ApplicationRepository
import dev.mstaszew.campaign.common.repo.ApplyTaskRepository
import dev.mstaszew.campaign.common.repo.BlockerRepository
import dev.mstaszew.campaign.common.repo.SkipRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Port of tick_status.sh: the campaign numbers on one call. */
data class CampaignStats(
    val submitted: Long,
    val attempted: Long,
    val target: Long = 2000,
    val queued: Long,
    val claimed: Long,
    val dead: Long,
    val skippedDuplicate: Long,
    val skippedSalary: Long,
    val skippedFilter: Long,
    val blockersOpen: Long,
) {
    val remaining: Long get() = (target - submitted).coerceAtLeast(0)
    val complete: Boolean get() = submitted >= target
}

@Service
class StatsService(
    private val applications: ApplicationRepository,
    private val tasks: ApplyTaskRepository,
    private val skips: SkipRepository,
    private val blockers: BlockerRepository,
) {

    @Transactional(readOnly = true)
    fun stats(): CampaignStats = CampaignStats(
        submitted = applications.countByStatus(ApplicationStatus.SUBMITTED),
        attempted = applications.countByStatus(ApplicationStatus.ATTEMPTED),
        queued = tasks.countByState(TaskState.QUEUED),
        claimed = tasks.countByState(TaskState.CLAIMED),
        dead = tasks.countByState(TaskState.DEAD),
        skippedDuplicate = skips.countByReason(SkipReason.DUPLICATE),
        skippedSalary = skips.countByReason(SkipReason.SALARY),
        skippedFilter = skips.countByReason(SkipReason.FILTER),
        blockersOpen = blockers.countByResolvedFalse(),
    )
}
