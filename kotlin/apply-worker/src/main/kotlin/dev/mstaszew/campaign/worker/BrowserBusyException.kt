package dev.mstaszew.campaign.worker

/**
 * The browser lease could not be taken within the wait budget (the finder's
 * discovery pass holds the shared Chrome). Treated like any retryable failure:
 * the consumer's catch path backs off, leaves the offset uncommitted, and the
 * broker redelivers. Deferral still burns attempt budget: the catch path
 * dead-letters the job once the attempt counter reaches maxAttempts, so a
 * candidate can exhaust its budget on busy rounds alone (bounded in practice
 * by the lease ttl - a stale holder lapses before that under ordinary
 * windows).
 */
class BrowserBusyException(jobId: String) :
    RuntimeException("browser busy; deferring $jobId for redelivery")
