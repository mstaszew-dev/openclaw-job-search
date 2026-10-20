package dev.mstaszew.campaign.common.repo

import com.mongodb.client.MongoClients
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The finder and the worker share ONE Chrome (lubuntu-agent CDP :30922). The
 * finder scans boards on a timer while the worker drives multi-minute apply
 * flows; without mutual exclusion they race for the same browser. This lease
 * is the mutual exclusion: one Mongo document, TTL-bounded so a crashed holder
 * self-heals instead of deadlocking the browser forever.
 *
 * Expiry semantics run on a settable clock: no sleeps, no scheduling noise,
 * exact boundary positions (just before / just after expiry, inside / past a
 * renewal). Only the two behaviors whose SUBJECT is real time - the renewal
 * watchdog's actual scheduling and the wait loop's actual polling - use the
 * system clock.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChromeLeaseIT {

    private class SettableClock(start: Instant) : Clock() {
        private var now = start

        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now

        fun advance(by: Duration) {
            now = now.plus(by)
        }
    }

    private val mongo: MongoTemplate =
        MongoTemplate(MongoClients.create(MongoReplicaSet.connectionUri), "chrome_lease_it")

    private val clock = SettableClock(Instant.parse("2026-10-20T12:00:00Z"))

    /** Deterministic lease: expiry moves only when the test moves the clock. */
    private val lease = ChromeLease(mongo, clock = clock)

    /** Real-clock lease for the tests whose subject IS real time. */
    private val realLease = ChromeLease(mongo)

    @BeforeEach
    fun clean() {
        mongo.dropCollection("browser_leases")
    }

    @Test
    fun `a second owner cannot acquire while the first holds the lease`() {
        val worker = lease.acquire(owner = "worker:w1", op = "apply", ttl = Duration.ofSeconds(30))

        assertThat(worker).isNotNull
        assertThat(lease.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))).isNull()
    }

    @Test
    fun `release lets the next owner in`() {
        val worker = lease.acquire(owner = "worker:w1", op = "apply", ttl = Duration.ofSeconds(30))!!

        worker.release()

        assertThat(lease.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))).isNotNull
    }

    @Test
    fun `an expired lease is stolen by a new owner`() {
        lease.acquire(owner = "worker:w1", op = "apply", ttl = Duration.ofSeconds(30))
        clock.advance(Duration.ofSeconds(31)) // a crashed pod's ttl lapses

        assertThat(lease.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))).isNotNull
    }

    @Test
    fun `the owner may re-acquire to refresh its own lease`() {
        lease.acquire(owner = "worker:w1", op = "apply", ttl = Duration.ofSeconds(30))

        assertThat(lease.acquire(owner = "worker:w1", op = "apply", ttl = Duration.ofSeconds(30))).isNotNull
        assertThat(lease.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))).isNull()
    }

    @Test
    fun `renew extends the lease for the owner but not for others`() {
        // Expiry starts at t=30s.
        lease.acquire(owner = "worker:w1", op = "apply", ttl = Duration.ofSeconds(30))

        clock.advance(Duration.ofSeconds(20)) // t=20s
        assertThat(lease.renew(owner = "worker:w1", ttl = Duration.ofSeconds(30))).isTrue()
        assertThat(lease.renew(owner = "finder", ttl = Duration.ofSeconds(30))).isFalse()

        clock.advance(Duration.ofSeconds(10)) // t=30s: past the ORIGINAL expiry,
        // but the renewal pushed expiry to t=50s, so the lease still holds.
        assertThat(lease.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))).isNull()

        clock.advance(Duration.ofSeconds(21)) // t=51s: past the renewed expiry too.
        assertThat(lease.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))).isNotNull
    }

    @Test
    fun `the renewal watchdog keeps the lease alive until release`() {
        // Real time is the subject here: ttl 500ms with renews every 150ms is
        // still held well past 1100ms only if the watchdog actually extends
        // the lease. This is what lets a real apply run longer than its base
        // ttl without being stolen.
        val worker = realLease.acquire(
            owner = "worker:w1",
            op = "apply",
            ttl = Duration.ofMillis(500),
            renewEvery = Duration.ofMillis(150),
        )!!

        Thread.sleep(1100)
        assertThat(realLease.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))).isNull()

        worker.release()
        assertThat(realLease.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))).isNotNull
    }

    @Test
    fun `acquire waits for a competing lease to expire within the wait budget`() {
        realLease.acquire(owner = "worker:w1", op = "apply", ttl = Duration.ofMillis(300))

        val finder = realLease.acquire(
            owner = "finder",
            op = "discover",
            ttl = Duration.ofSeconds(30),
            wait = Duration.ofSeconds(10),
            poll = Duration.ofMillis(100),
        )

        // The wait loop polls until the 300ms lease lapses, then takes over.
        assertThat(finder).isNotNull
    }

    @Test
    fun `a stale owner's late release cannot wipe the new holder's lease`() {
        // Simulates: worker crashes mid-apply, ttl lapses, finder takes the
        // browser, THEN the worker's last-gasp finally block runs anyway
        // (pod pause, slow shutdown hook). The finder must keep the browser.
        val worker = lease.acquire(owner = "worker:w1", op = "apply", ttl = Duration.ofSeconds(30))!!
        clock.advance(Duration.ofSeconds(31))
        assertThat(lease.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))).isNotNull

        worker.release() // late, owner no longer the holder: must be a no-op

        assertThat(lease.renew(owner = "finder", ttl = Duration.ofSeconds(30))).isTrue()
        assertThat(lease.acquire(owner = "worker:w2", op = "apply", ttl = Duration.ofSeconds(30))).isNull()
    }

    @Test
    fun `a double release removes the lease exactly once`() {
        val worker = lease.acquire(owner = "worker:w1", op = "apply", ttl = Duration.ofSeconds(30))!!

        assertThatCode {
            worker.release()
            worker.release()
        }.doesNotThrowAnyException()

        // The second release must not have re-created or re-removed anything:
        // the very next acquirer gets in immediately.
        assertThat(lease.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))).isNotNull
    }

    @Test
    fun `a disabled lease hands out handles without mutual exclusion`() {
        val disabled = ChromeLease(mongo, enabled = false)

        val first = disabled.acquire(owner = "worker:w1", op = "apply", ttl = Duration.ofSeconds(30))
        val second = disabled.acquire(owner = "finder", op = "discover", ttl = Duration.ofSeconds(30))

        assertThat(first).isNotNull
        assertThat(second).isNotNull
        first!!.release() // kill switch must be a safe no-op, not a crash
        assertThat(mongo.getCollectionNames().contains("browser_leases")).isFalse()
    }
}
