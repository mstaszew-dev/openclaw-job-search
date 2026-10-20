package dev.mstaszew.campaign.common.repo

import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Handle is the one piece of the lease every caller touches in a finally
 * block, so it must be unconditionally safe: a release that throws would
 * replace the real failure (the apply error that decided retry-vs-DLQ) with
 * a bookkeeping blip.
 */
class ChromeLeaseHandleTest {

    @Test
    fun `release swallows an internal failure instead of masking the caller's exception`() {
        val handle = ChromeLease.Handle.of { error("mongo blip during release") }

        assertThatCode { handle.release() }.doesNotThrowAnyException()
    }

    @Test
    fun `release is idempotent - the second call is a no-op`() {
        var releases = 0
        val handle = ChromeLease.Handle.of { releases++ }

        handle.release()
        handle.release()

        assertThatCode { }.doesNotThrowAnyException()
        check(releases == 1) { "expected exactly one release, got $releases" }
    }

    @Test
    fun `close releases exactly once`() {
        var releases = 0
        val handle = ChromeLease.Handle.of { releases++ }

        handle.use { }

        check(releases == 1) { "expected exactly one release, got $releases" }
    }
}
