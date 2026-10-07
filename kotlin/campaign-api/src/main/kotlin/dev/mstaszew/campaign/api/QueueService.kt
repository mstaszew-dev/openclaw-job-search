package dev.mstaszew.campaign.api

import dev.mstaszew.campaign.api.error.ConflictException
import dev.mstaszew.campaign.api.error.NotFoundException
import dev.mstaszew.campaign.common.domain.TaskState
import dev.mstaszew.campaign.common.repo.ApplyTaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueueService(private val tasks: ApplyTaskRepository) {

    /**
     * Requeues a FAILED or DEAD task for one more attempt. SUBMITTED/ATTEMPTED
     * are deliberately excluded: requeueing an applied listing would violate
     * the campaign's one-company-once rule.
     */
    @Transactional
    fun requeue(id: Long): RequeueResponse {
        val task = tasks.findById(id).orElseThrow { NotFoundException("task $id") }
        if (task.state.isActive || task.state in REAPPLICABLE) {
            throw ConflictException("task $id in state ${task.state} cannot be requeued")
        }
        task.transitionTo(TaskState.QUEUED)
        task.attempts = 0
        return RequeueResponse(task.id, task.state.name)
    }

    companion object {
        private val REAPPLICABLE = setOf(TaskState.SUBMITTED, TaskState.ATTEMPTED)
    }
}
