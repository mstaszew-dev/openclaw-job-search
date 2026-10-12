package dev.mstaszew.campaign.common.domain

enum class ApplicationStatus {
    SUBMITTED,
    ATTEMPTED,
}

enum class SkipReason {
    DUPLICATE,
    SALARY,
    FILTER,
}
