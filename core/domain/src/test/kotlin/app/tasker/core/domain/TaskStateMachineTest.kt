package app.tasker.core.domain

import app.tasker.core.domain.status.InvalidTransitionException
import app.tasker.core.domain.status.TaskAction
import app.tasker.core.domain.status.TaskStateMachine
import app.tasker.core.model.TaskStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskStateMachineTest {
    @Test
    fun `allowed transitions follow EXC-1 and interpretation 3`() {
        val expected = mapOf(
            (TaskStatus.OPEN to TaskAction.START) to TaskStatus.IN_PROGRESS,
            (TaskStatus.IN_PROGRESS to TaskAction.PAUSE) to TaskStatus.PAUSED,
            (TaskStatus.PAUSED to TaskAction.RESUME) to TaskStatus.IN_PROGRESS,
            (TaskStatus.OPEN to TaskAction.COMPLETE) to TaskStatus.DONE,
            (TaskStatus.IN_PROGRESS to TaskAction.COMPLETE) to TaskStatus.DONE,
            (TaskStatus.PAUSED to TaskAction.COMPLETE) to TaskStatus.DONE,
            (TaskStatus.DONE to TaskAction.REOPEN) to TaskStatus.OPEN,
            (TaskStatus.ARCHIVED to TaskAction.RESTORE) to TaskStatus.OPEN,
        ) + TaskStatus.entries.filter { it != TaskStatus.ARCHIVED }.associate { (it to TaskAction.ARCHIVE) to TaskStatus.ARCHIVED }

        for (status in TaskStatus.entries) {
            for (action in TaskAction.entries) {
                assertThat(TaskStateMachine.target(status, action)).isEqualTo(expected[status to action])
            }
        }
    }

    @Test
    fun `invalid transition is an error, not a silent no-op`() {
        assertThrows(InvalidTransitionException::class.java) {
            TaskStateMachine.transition(TaskStatus.DONE, TaskAction.START)
        }
        assertThrows(InvalidTransitionException::class.java) {
            TaskStateMachine.transition(TaskStatus.ARCHIVED, TaskAction.ARCHIVE)
        }
    }
}
