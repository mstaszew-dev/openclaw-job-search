package dev.mstaszew.campaign.common.domain

/**
 * Lifecycle of one apply task. Active states have an open task row per listing
 * (see uq_apply_tasks_active); terminal states keep the history.
 */
enum class TaskState {
    DISCOVERED,
    QUEUED,
    CLAIMED,
    SUBMITTED,
    ATTEMPTED,
    SKIPPED_DUPLICATE,
    SKIPPED_SALARY,
    SKIPPED_FILTER,
    BLOCKED,
    FAILED,
    DEAD,
    SHADOW_RELEASED;

    val isActive: Boolean
        get() = this == DISCOVERED || this == QUEUED || this == CLAIMED

    val isTerminal: Boolean
        get() = !isActive
}
