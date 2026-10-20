package dev.mstaszew.campaign.common.repo

import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mutual exclusion for the ONE shared campaign Chrome (lubuntu-agent CDP).
 *
 * The finder's discovery passes and the worker's live applies both drive the
 * same browser; without a lease they race for tabs and focus. One Mongo
 * document (`browser_leases`, _id "chrome") is the whole mechanism.
 *
 * Safety properties, in the order they matter:
 * - TTL-bounded: a holder that crashes without releasing wedges the browser
 *   for at most its ttl, then the next acquirer steals. No permanent deadlock.
 * - Owner-guarded release: only the current owner's key deletes the document,
 *   so a late release after a ttl steal can never wipe someone else's lease.
 * - Self-renewing holds: `renewEvery` runs a daemon watchdog that keeps
 *   extending the ttl while the holder is still working, for ops that can
 *   legitimately outlive their base ttl (the worker's worst-case apply loop
 *   is ~87 min). A dead holder stops renewing and the lease lapses.
 * - Kill switch: `chrome.lease.enabled=false` (env CHROME_LEASE_ENABLED)
 *   hands out no-op handles, so the mechanism can be disarmed without code
 *   changes and without touching either call site.
 * - Clock: expiry is a pod wall-clock [Date] compared against Mongo's stored
 *   value, so modest multi-second skew between nodes is tolerated by design;
 *   the ttls in play are minutes, and a skewed clock shifts a steal by that
 *   skew, not by the whole ttl.
 */
@Component
class ChromeLease(
    private val mongo: MongoTemplate,
    @Value("\${chrome.lease.enabled:true}") private val enabled: Boolean = true,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(ChromeLease::class.java)

    /**
     * Takes the lease, or returns null when another owner holds it unexpired.
     * With [wait] set, polls every [poll] until the budget is spent, so a
     * caller can queue for a short, bounded stretch instead of failing
     * immediately. With [renewEvery] set, the returned handle keeps itself
     * alive until [Handle.release].
     */
    fun acquire(
        owner: String,
        op: String,
        ttl: Duration,
        wait: Duration = Duration.ZERO,
        poll: Duration = Duration.ofSeconds(2),
        renewEvery: Duration? = null,
    ): Handle? {
        if (!enabled) return Handle.noop()

        val deadline = System.nanoTime() + wait.toNanos()
        while (true) {
            if (tryTake(owner, op, ttl)) {
                val renewer = renewEvery?.let { startRenewer(owner, ttl, it) }
                return Handle.of {
                    renewer?.stop()
                    removeLease(owner)
                }
            }
            if (System.nanoTime() >= deadline) return null
            try {
                Thread.sleep(poll.toMillis())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    /** Extends the caller's expiry. False means the lease is gone or stolen. */
    fun renew(owner: String, ttl: Duration): Boolean {
        if (!enabled) return true
        val updated = mongo.findAndModify(
            Query(Criteria.where("_id").`is`(LEASE_ID).and(F_OWNER).`is`(owner)),
            Update().set(F_EXPIRES, expiry(ttl)),
            FindAndModifyOptions.options().returnNew(false),
            Document::class.java,
            COLLECTION,
        )
        return updated != null
    }

    /** Insert-if-free, then take over an expired or own (refresh) lease. */
    private fun tryTake(owner: String, op: String, ttl: Duration): Boolean {
        val now = clock.instant()
        val fresh = Document()
            .append("_id", LEASE_ID)
            .append(F_OWNER, owner)
            .append(F_OP, op)
            .append(F_ACQUIRED, Date.from(now))
            .append(F_EXPIRES, expiry(ttl))
        return try {
            mongo.insert(fresh, COLLECTION)
            true
        } catch (e: DuplicateKeyException) {
            val previous = mongo.findAndModify(
                Query(
                    Criteria.where("_id").`is`(LEASE_ID)
                        .orOperator(
                            Criteria.where(F_EXPIRES).lte(Date.from(now)),
                            Criteria.where(F_OWNER).`is`(owner),
                        ),
                ),
                leaseUpdate(owner, op, ttl, now),
                FindAndModifyOptions.options().returnNew(false),
                Document::class.java,
                COLLECTION,
            )
            if (previous != null) {
                val previousOwner = previous.getString(F_OWNER)
                if (previousOwner != owner) {
                    log.info("chrome lease stolen from {} by {} (expired)", previousOwner, owner)
                }
                true
            } else {
                false
            }
        }
    }

    private fun startRenewer(owner: String, ttl: Duration, every: Duration): Renewer {
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "chrome-lease-renew").apply { isDaemon = true }
        }
        // Throwing from a fixed-rate task cancels the schedule by JDK contract;
        // [LeaseLostMarker] is the only thrower, so transient Mongo blips log
        // and retry while a genuinely lost lease stops the watchdog cleanly.
        val future = executor.scheduleAtFixedRate({
            try {
                if (!renew(owner, ttl)) throw LeaseLostMarker
            } catch (e: LeaseLostMarker) {
                throw e
            } catch (e: Exception) {
                log.warn("chrome lease renewal for {} failed (will retry): {}", owner, e.message)
            }
        }, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS)
        return Renewer(executor, future)
    }

    private fun removeLease(owner: String) {
        val removed = mongo.remove(
            Query(Criteria.where("_id").`is`(LEASE_ID).and(F_OWNER).`is`(owner)),
            COLLECTION,
        )
        if (removed.deletedCount == 0L) {
            log.warn("chrome lease release by {} found nothing to remove (already stolen?)", owner)
        }
    }

    private fun leaseUpdate(owner: String, op: String, ttl: Duration, now: Instant): Update =
        Update()
            .set(F_OWNER, owner)
            .set(F_OP, op)
            .set(F_ACQUIRED, Date.from(now))
            .set(F_EXPIRES, expiry(ttl))

    private fun expiry(ttl: Duration): Date = Date.from(clock.instant().plus(ttl))

    private data class Renewer(val executor: ScheduledExecutorService, val future: ScheduledFuture<*>) {
        fun stop() {
            future.cancel(false)
            executor.shutdownNow()
        }
    }

    private object LeaseLostMarker : RuntimeException(null, null, false, false)

    /**
     * A held lease. [release] is idempotent and owner-guarded: the underlying
     * document is removed at most once, and only while this handle's owner is
     * still the recorded holder. Release is also deliberately infallible: it
     * runs in callers' finally blocks and must never replace their primary
     * failure with a bookkeeping blip.
     */
    class Handle private constructor(
        private val releaseAction: (() -> Unit)?,
    ) : AutoCloseable {

        private val log = LoggerFactory.getLogger(Handle::class.java)

        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) {
                try {
                    releaseAction?.invoke()
                } catch (e: Exception) {
                    log.warn("chrome lease release failed", e)
                }
            }
        }

        override fun close() = release()

        internal companion object {
            fun noop() = Handle(null)

            fun of(releaseAction: () -> Unit) = Handle(releaseAction)
        }
    }

    private companion object {
        const val COLLECTION = "browser_leases"
        const val LEASE_ID = "chrome"
        const val F_OWNER = "owner"
        const val F_OP = "op"
        const val F_ACQUIRED = "acquiredAt"
        const val F_EXPIRES = "expiresAt"
    }
}

