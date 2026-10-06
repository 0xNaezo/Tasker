package app.tasker.core.domain

import app.tasker.core.domain.history.TaskColumns
import app.tasker.core.domain.history.UndoPlanner
import app.tasker.core.domain.rules.Rules
import app.tasker.core.model.Bucket
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.FieldSource
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.TestClock
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import org.junit.Test

class UndoAndHistoryTest {
    private val clock = TestClock().clock
    private val today = date("2026-10-06")

    @Test
    fun `diff records only changed columns and round trips through json`() {
        val before = aTask(bucket = Bucket.WEEK, estimate = Estimate.S)
        val after = before.copy(
            bucket = Bucket.TODAY,
            deadline = Deadline(date("2026-10-09"), LocalTime.of(18, 0), clock.zone()),
            fieldSources = mapOf(TaskField.DEADLINE to FieldSource.PARSER),
        )
        val changes = TaskColumns.diff(before, after)
        assertThat(changes.map { it.field }).containsExactly("bucket", "deadline", "field_sources").inOrder()
        val reverted = UndoPlanner.revert(after, changes, TaskColumns, revertMarkers = true)
        assertThat(reverted.entity).isEqualTo(before)
    }

    @Test
    fun `automation undo reverts counters by delta and keeps idempotency markers`() {
        val task = aTask(planDate = date("2026-10-05"))
        val outcome = Rules.planDatePassed(task, today)!!
        val changes = TaskColumns.diff(outcome.before, outcome.after)
        val laterIncrement = outcome.after.copy(postponeCount = 3)
        val reverted = UndoPlanner.revert(laterIncrement, changes, TaskColumns, revertMarkers = false).entity
        assertThat(reverted.postponeCount).isEqualTo(2)
        assertThat(reverted.planDateRolled).isEqualTo(date("2026-10-05"))
        assertThat(Rules.planDatePassed(reverted, today)).isNull()
    }

    @Test
    fun `counters never go below zero`() {
        val task = aTask(planDate = date("2026-10-05"))
        val outcome = Rules.planDatePassed(task, today)!!
        val changes = TaskColumns.diff(outcome.before, outcome.after)
        val reverted = UndoPlanner.revert(outcome.after.copy(postponeCount = 0), changes, TaskColumns, revertMarkers = false)
        assertThat(reverted.entity.postponeCount).isEqualTo(0)
    }

    @Test
    fun `fields changed by the user afterwards are reported, not reverted`() {
        val task = aTask(inReview = true, reviewSkipStreak = 2)
        val archive = Rules.autoArchive(task, clock.now(), today, clock)!!
        val changes = TaskColumns.diff(archive.before, archive.after)
        val edited = archive.after.copy(bucket = Bucket.SOMEDAY, status = TaskStatus.ARCHIVED, archiveReason = null)
        val reverted = UndoPlanner.revert(edited, changes, TaskColumns, revertMarkers = false)
        assertThat(reverted.entity.status).isEqualTo(TaskStatus.OPEN)
        assertThat(reverted.conflictingFields).contains("archive_reason")
        assertThat(reverted.entity.bucket).isEqualTo(Bucket.SOMEDAY)
    }
}
