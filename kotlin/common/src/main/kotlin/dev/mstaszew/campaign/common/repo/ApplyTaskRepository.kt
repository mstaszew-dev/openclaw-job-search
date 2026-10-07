package dev.mstaszew.campaign.common.repo

import dev.mstaszew.campaign.common.domain.ApplyTaskEntity
import dev.mstaszew.campaign.common.domain.TaskState
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface ApplyTaskRepository : JpaRepository<ApplyTaskEntity, Long> {

    /**
     * Atomically claims the highest-priority QUEUED task. SKIP LOCKED keeps
     * concurrent workers from picking the same row; make_interval bounds the
     * lease so a dead worker's task is reaped back to QUEUED (or DEAD when
     * attempts are exhausted). The returned entity reflects the DB row only
     * if the caller's persistence context does not already hold it managed.
     */
    @Transactional
    @Query(
        value = """
            UPDATE apply_tasks
               SET state = 'CLAIMED', claimed_by = :worker, attempts = attempts + 1,
                   claim_expires_at = now() + make_interval(secs => :leaseSeconds),
                   state_changed_at = now(), updated_at = now()
             WHERE id = (
                   SELECT id FROM apply_tasks
                    WHERE state = 'QUEUED'
                    ORDER BY priority DESC, id ASC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
             )
            RETURNING *
        """,
        nativeQuery = true,
    )
    fun claim(
        @Param("worker") worker: String,
        @Param("leaseSeconds") leaseSeconds: Int,
    ): ApplyTaskEntity?

    /** Requeues expired CLAIMED tasks; DEAD when attempts are exhausted. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            UPDATE apply_tasks
               SET state = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'QUEUED' END,
                   claimed_by = NULL, claim_expires_at = NULL,
                   state_changed_at = now(), updated_at = now()
             WHERE state = 'CLAIMED' AND claim_expires_at < now()
        """,
        nativeQuery = true,
    )
    fun releaseExpiredClaims(): Int

    fun countByState(state: TaskState): Long

    fun findByStateOrderByPriorityDescIdAsc(state: TaskState): List<ApplyTaskEntity>
}
