package app.tasker.core.domain

import app.tasker.core.domain.order.Positions
import app.tasker.core.domain.provenance.FieldProvenance
import app.tasker.core.domain.similar.SimilarTasks
import app.tasker.core.domain.split.SplitPlanner
import app.tasker.core.model.Bucket
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.FieldSource
import app.tasker.core.model.TaskField
import app.tasker.core.model.UuidV7
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MiscDomainTest {
    @Test
    fun `AI writes only empty, AI or default fields`() {
        assertThat(FieldProvenance.canWrite(null, valuePresent = false, writer = FieldSource.AI)).isTrue()
        assertThat(FieldProvenance.canWrite(FieldSource.AI, valuePresent = true, writer = FieldSource.AI)).isTrue()
        assertThat(FieldProvenance.canWrite(FieldSource.PARSER, valuePresent = true, writer = FieldSource.AI)).isFalse()
        assertThat(FieldProvenance.canWrite(FieldSource.USER, valuePresent = true, writer = FieldSource.INTEGRATION)).isFalse()
        assertThat(FieldProvenance.canWrite(FieldSource.INTEGRATION, valuePresent = true, writer = FieldSource.INTEGRATION)).isTrue()
        val task = FieldProvenance.mark(aTask(estimate = Estimate.M), FieldSource.AI, TaskField.ESTIMATE, TaskField.DEADLINE)
        assertThat(task.fieldSources).containsExactly(TaskField.ESTIMATE, FieldSource.AI)
        assertThat(FieldProvenance.aiFields(task)).containsExactly(TaskField.ESTIMATE)
    }

    @Test
    fun `positions insert between neighbours and ask for rebalance when adjacent`() {
        assertThat(Positions.between(Positions.STEP, 2 * Positions.STEP)).isEqualTo(Positions.STEP + Positions.STEP / 2)
        assertThat(Positions.between(10, 11)).isNull()
        assertThat(Positions.positionForMove(listOf(100, 200, 300), from = 2, to = 0)).isEqualTo(100 - Positions.STEP)
        assertThat(Positions.rebalanced(3)).containsExactly(Positions.STEP, 2 * Positions.STEP, 3 * Positions.STEP).inOrder()
    }

    @Test
    fun `split gives the plan place to the first step and the deadline to the last`() {
        val task = aTask(bucket = Bucket.TODAY, planDate = date("2026-10-06"), deadline = Deadline(date("2026-10-09")))
            .copy(note = "context")
        val seeds = SplitPlanner.seeds(task, listOf("- draft", "review", "", "send"))
        assertThat(seeds.map { it.text }).containsExactly("draft", "review", "send").inOrder()
        assertThat(seeds.first().bucket).isEqualTo(Bucket.TODAY)
        assertThat(seeds.first().takesPlanSlot).isTrue()
        assertThat(seeds.first().note).isEqualTo("context")
        assertThat(seeds[1].bucket).isEqualTo(Bucket.WEEK)
        assertThat(seeds.last().deadline).isEqualTo(task.deadline)
        assertThat(SplitPlanner.isValid(listOf("only one"))).isFalse()
    }

    @Test
    fun `similar tasks pick lexically close texts`() {
        val done = listOf("Сдать отчёт клиенту", "Купить молоко", "Отчет по продажам за квартал")
        val picked = SimilarTasks.pick("сдать отчет за месяц", done, limit = 2) { it }
        assertThat(picked.map { it.item }).containsExactly("Сдать отчёт клиенту", "Отчет по продажам за квартал").inOrder()
    }

    @Test
    fun `uuid v7 sorts by time and keeps the timestamp`() {
        val first = UuidV7.generate(1_000)
        val second = UuidV7.generate(2_000)
        assertThat(first < second).isTrue()
        assertThat(UuidV7.timestampOf(second)).isEqualTo(2_000)
        assertThat(java.util.UUID.fromString(first).version()).isEqualTo(7)
    }
}
