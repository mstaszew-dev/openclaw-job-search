package dev.mstaszew.campaign.worker

/**
 * The browser lease could not be taken within the wait budget (the finder's
 * discovery pass holds the shared Chrome). Treated like any retryable failure:
 * the consumer's catch path backs off, leaves the offset uncommitted, and the
 * broker redelivers, so deferral costs an attempt but never the job.
 */
class BrowserBusyException(jobId: String) :
    RuntimeException("browser busy; deferring $jobId for redelivery")
