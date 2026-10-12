package dev.mstaszew.campaign.api

import dev.mstaszew.campaign.common.domain.ApplicationStatus
import dev.mstaszew.campaign.common.domain.JobStatus
import dev.mstaszew.campaign.common.domain.SkipReason
import dev.mstaszew.campaign.common.messaging.ConsumerLagProbe
import dev.mstaszew.campaign.common.messaging.Topics
import dev.mstaszew.campaign.common.repo.ApplicationRepository
import dev.mstaszew.campaign.common.repo.BlockerRepository
import dev.mstaszew.campaign.common.repo.JobStateRepository
import dev.mstaszew.campaign.common.repo.SkipRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Port of tick_status.sh: the campaign numbers on one call.
 *
 * queued/claimed/dead are gone. They were columns on the apply_tasks table,
 * and Kafka has no addressable row to count. What replaces them:
 *
 * - inFlight, from job_state: messages taken but not yet finished.
 * - dlqDepth, from the dead-letter topic's offset.
 * - consumerLag, from the consumer group: messages produced but not consumed.
 *
 * Those three together are the queue view, and unlike the old columns they
 * cannot disagree with each other, because two of them are read from Kafka
 * itself rather than from a table the pipeline also writes.
 */
data class CampaignStats(
    val submitted: Long,
    val attempted: Long,
    val target: Long = 2000,
    val inFlight: Long,
    val dead: Long,
    val dlqDepth: Long,
    val consumerLag: Long?,
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
    private val jobState: JobStateRepository,
    private val skips: SkipRepository,
    private val blockers: BlockerRepository,
    private val lagProbe: ConsumerLagProbe,
) {

    @Transactional(readOnly = true)
    fun stats(): CampaignStats = CampaignStats(
        submitted = applications.countByStatus(ApplicationStatus.SUBMITTED),
        attempted = applications.countByStatus(ApplicationStatus.ATTEMPTED),
        inFlight = jobState.countByStatus(JobStatus.IN_FLIGHT) + jobState.countByStatus(JobStatus.PENDING),
        dead = jobState.countByStatus(JobStatus.DEAD),
        dlqDepth = lagProbe.lag(Topics.APPLY_GROUP, Topics.JOBS_DLQ) ?: 0L,
        consumerLag = lagProbe.lag(Topics.APPLY_GROUP, Topics.JOBS),
        skippedDuplicate = skips.countByReason(SkipReason.DUPLICATE),
        skippedSalary = skips.countByReason(SkipReason.SALARY),
        skippedFilter = skips.countByReason(SkipReason.FILTER),
        blockersOpen = blockers.countByResolvedFalse(),
    )
}