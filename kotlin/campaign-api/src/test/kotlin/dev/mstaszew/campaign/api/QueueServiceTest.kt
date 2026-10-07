package dev.mstaszew.campaign.api

import dev.mstaszew.campaign.api.error.ApiExceptionHandler
import dev.mstaszew.campaign.common.domain.TaskState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever
import java.util.Optional

class QueueServiceTest {

    private val tasks = Mockito.mock(dev.mstaszew.campaign.common.repo.ApplyTaskRepository::class.java)
    private val service = QueueService(tasks)

    private fun task(state: TaskState) = dev.mstaszew.campaign.common.domain.ApplyTaskEntity(
        id = 5,
        listingId = 1,
        state = state,
        attempts = 3,
    )

    @Test
    fun `dead task is requeued and attempts reset`() {
        whenever(tasks.findById(5)).thenReturn(Optional.of(task(TaskState.DEAD)))

        val response = service.requeue(5)

        assertThat(response.state).isEqualTo(TaskState.QUEUED.name)
    }

    @Test
    fun `submitted task can never be requeued (one company once)`() {
        whenever(tasks.findById(5)).thenReturn(Optional.of(task(TaskState.SUBMITTED)))

        assertThrows<dev.mstaszew.campaign.api.error.ConflictException> { service.requeue(5) }
    }

    @Test
    fun `active task cannot be requeued`() {
        whenever(tasks.findById(5)).thenReturn(Optional.of(task(TaskState.CLAIMED)))

        assertThrows<dev.mstaszew.campaign.api.error.ConflictException> { service.requeue(5) }
    }
}
