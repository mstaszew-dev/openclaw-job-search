package dev.mstaszew.campaign.common.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TaskStateTest {

    @Test
    fun `active states are exactly discovered queued claimed`() {
        assertThat(TaskState.entries.filter { it.isActive })
            .containsExactlyInAnyOrder(TaskState.DISCOVERED, TaskState.QUEUED, TaskState.CLAIMED)
    }

    @Test
    fun `every state is either active or terminal`() {
        assertThat(TaskState.entries).allSatisfy { state ->
            assertThat(state.isActive != state.isTerminal).isTrue()
        }
    }

    @Test
    fun `terminal transition clears lease fields`() {
        val task = ApplyTaskEntity(
            listingId = 1,
            state = TaskState.CLAIMED,
            claimedBy = "worker-1",
            claimExpiresAt = java.time.Instant.now().plusSeconds(60),
        )
        task.transitionTo(TaskState.SUBMITTED)
        assertThat(task.state).isEqualTo(TaskState.SUBMITTED)
        assertThat(task.claimedBy).isNull()
        assertThat(task.claimExpiresAt).isNull()
    }

    @Test
    fun `active-to-active transition keeps lease fields`() {
        val task = ApplyTaskEntity(listingId = 1, state = TaskState.DISCOVERED)
        task.transitionTo(TaskState.QUEUED)
        assertThat(task.state).isEqualTo(TaskState.QUEUED)
    }
}
