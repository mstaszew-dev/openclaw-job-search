package dev.mstaszew.campaign.worker

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.dedupe.Candidate
import dev.mstaszew.campaign.common.dedupe.DedupService
import dev.mstaszew.campaign.common.domain.EventEntity
import dev.mstaszew.campaign.common.domain.JobListingEntity
import dev.mstaszew.campaign.common.domain.TaskState
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.common.repo.ApplyTaskRepository
import dev.mstaszew.campaign.common.repo.EventRepository
import dev.mstaszew.campaign.common.repo.JobListingRepository
import dev.mstaszew.campaign.worker.apply.ApplyResult
import dev.mstaszew.campaign.worker.apply.BrowserAgent
import dev.mstaszew.campaign.worker.apply.Recorder
import dev.mstaszew.campaign.worker.apply.SubmissionVerifier
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * The apply loop: reap expired leases, claim one QUEUED task, dispatch by mode.
 * - shadow: re-checks dedup, then releases the task (SHADOW_RELEASED). No
 *   browser, no application rows: the whole pipeline is exercised against the
 *   imported tracker with zero duplicate-apply risk.
 * - live: BrowserAgent drives the shared Chrome over CDP; results are recorded
 *   by the Recorder (submitted/attempted/skip/blocker).
 */
@Component
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerLoop(
    private val tasks: ApplyTaskRepository,
    private val listings: JobListingRepository,
    private val events: EventRepository,
    private val dedup: DedupService,
    private val recorder: Recorder,
    private val browserAgent: BrowserAgent,
    private val props: WorkerProperties,
    private val tx: TransactionTemplate,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(WorkerLoop::class.java)

    @Scheduled(fixedDelayString = "\${worker.reaper-interval-seconds:60}000")
    fun reapExpiredLeases() {
        val released = tasks.releaseExpiredClaims()
        if (released > 0) log.info("reaper released {} expired claims", released)
    }

    @Scheduled(fixedDelayString = "\${worker.poll-interval-seconds:30}000", initialDelayString = "15000")
    fun tick() {
        val claimed = tasks.claim(props.workerId, props.leaseSeconds) ?: return
        val listing = listings.findById(claimed.listingId).orElse(null)
        if (listing == null) {
            log.error("task {} references missing listing {}; releasing as FAILED", claimed.id, claimed.listingId)
            failTask(claimed.id)
            return
        }
        log.info("claimed task {} for {} at {}", claimed.id, listing.source, listing.company)
        try {
            when (props.applyMode) {
                "live" -> liveApply(claimed.id, listing)
                else -> shadowRelease(claimed.id, listing)
            }
        } catch (e: Exception) {
            log.error("task {} crashed: {}", claimed.id, e.message, e)
            // lease expiry + attempts bound decide retry vs DEAD
        }
    }

    /** Persisted in one short transaction; explicit save (no dirty-checking reliance). */
    fun shadowRelease(taskId: Long, listing: JobListingEntity) {
        val decision = dedup.check(
            Candidate(
                source = listing.source,
                sourceJobId = listing.sourceJobId,
                company = listing.company,
                companyKey = listing.companyKey,
                roleTitle = listing.roleTitle,
                url = listing.url,
                region = listing.region,
            ),
        )
        tx.executeWithoutResult {
            events.save(
                EventEntity(
                    action = "shadow_released",
                    record = mapper.writeValueAsString(
                        mapper.createObjectNode().apply {
                            put("id", "${listing.source}:${listing.sourceJobId}")
                            put("duplicate", decision.duplicate)
                            put("matchedOn", decision.matchedOn?.name ?: "none")
                        },
                    ),
                ),
            )
            tasks.findById(taskId).ifPresent { task ->
                task.transitionTo(TaskState.SHADOW_RELEASED)
                tasks.save(task)
            }
        }
        if (decision.duplicate) log.info("shadow: task {} is a duplicate ({})", taskId, decision.matchedOn)
    }

    private fun failTask(taskId: Long) {
        tx.executeWithoutResult {
            tasks.findById(taskId).ifPresent { task ->
                task.transitionTo(TaskState.FAILED)
                tasks.save(task)
            }
        }
    }

    private fun liveApply(taskId: Long, listing: JobListingEntity) {
        val result = browserAgent.run(
            taskId = taskId,
            listingId = listing.id,
            applicantProfileText = System.getenv("APPLICANT_PROFILE_TEXT") ?: "see mounted applicant profile",
            cvPathPl = System.getenv("CV_PATH_PL") ?: "/cv/michael-staszewski-cv-pl.pdf",
            mcpFactory = {
                McpStdioClient(
                    (System.getenv("WORKER_MCP_COMMAND")
                        ?: "node @playwright/mcp/cli.js --cdp-endpoint http://127.0.0.1:9222")
                        .split(' '),
                )
            },
        )
        when (result) {
            is ApplyResult.Submitted -> log.info(
                "task {}: recorded (valid={})",
                taskId,
                SubmissionVerifier.evaluate(result.confirmationUrl, result.confirmationText).valid,
            )
            is ApplyResult.DuplicateNow -> recorder.recordSkippedDuplicate(listing, taskId, result.detail)
            is ApplyResult.Blocked -> recorder.recordBlocked(listing, taskId, result.reason, result.detail)
            is ApplyResult.Failed -> {
                log.warn("task {} failed: {}", taskId, result.detail)
                failTask(taskId)
            }
        }
    }
}
