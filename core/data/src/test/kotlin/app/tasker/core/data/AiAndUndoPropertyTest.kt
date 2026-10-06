package app.tasker.core.data

import app.tasker.core.data.ai.AiApplyResult
import app.tasker.core.data.ai.AiFill
import app.tasker.core.data.command.FieldUpdate
import app.tasker.core.data.command.TaskEdit
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.Actor
import app.tasker.core.model.AiSettings
import app.tasker.core.model.AppSettings
import app.tasker.core.model.EnrichState
import app.tasker.core.model.EntityType
import app.tasker.core.model.Estimate
import app.tasker.core.model.EventType
import app.tasker.core.model.FieldSource
import app.tasker.core.model.ReviewKind
import app.tasker.core.model.TaskField
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiAndUndoPropertyTest {
    private val aiOn = AppSettings(
        parserLanguages = setOf("ru", "uk", "en"),
        ai = AiSettings(enabled = true, consentAtEpochMillis = 1L),
    )
    private val env = DataEnv(settings = aiOn)

    @After
    fun tearDown() = env.close()

    @Test
    fun `ai fills only empty fields, never touches the task and can be undone`() = runTest {
        val task = env.add("подготовить презентацию до 20.10")
        assertThat(task.enrichState).isEqualTo(EnrichState.PENDING)
        val job = checkNotNull(env.ai.job(task.id))
        env.time.advanceHours(1)

        val result = env.ai.apply(
            task.id,
            job.textHash,
            AiFill(
                estimate = Estimate.L,
                estimateConfidence = 0.8,
                deadline = app.tasker.core.model.Deadline(app.tasker.core.testing.date("2026-10-25")),
            ),
        ).value
        assertThat(result).isEqualTo(AiApplyResult.APPLIED)
        val filled = env.task(task.id)
        assertThat(filled.estimate).isEqualTo(Estimate.L)
        assertThat(filled.deadline?.date).isEqualTo(app.tasker.core.testing.date("2026-10-20"))
        assertThat(filled.fieldSources[TaskField.ESTIMATE]).isEqualTo(FieldSource.AI)
        assertThat(filled.lastTouchedAt).isEqualTo(task.lastTouchedAt)
        val event = env.events(task.id).single { it.type == EventType.AI_FILLED }
        assertThat(event.actor).isEqualTo(Actor.AI)
        assertThat(event.reason?.params?.get("fields")).isEqualTo("ESTIMATE")

        env.undo.undoAiFill(task.id)
        val undone = env.task(task.id)
        assertThat(undone.estimate).isNull()
        assertThat(undone.fieldSources).doesNotContainKey(TaskField.ESTIMATE)
        assertThat(undone.enrichState).isEqualTo(EnrichState.DONE)
    }

    @Test
    fun `a result for edited text is discarded`() = runTest {
        val task = env.add("написать письмо")
        val job = checkNotNull(env.ai.job(task.id))
        env.tasks.edit(task.id, TaskEdit(title = FieldUpdate.Set("написать два письма")))
        assertThat(env.ai.apply(task.id, job.textHash, AiFill(estimate = Estimate.S)).value).isEqualTo(AiApplyResult.STALE)
        assertThat(env.task(task.id).estimate).isNull()
    }

    @Test
    fun `the user's estimate is never overwritten by ai`() = runTest {
        val task = env.add("написать письмо")
        val job = checkNotNull(env.ai.job(task.id))
        env.tasks.edit(task.id, TaskEdit(estimate = FieldUpdate.Set(Estimate.S)))
        val result = env.ai.apply(task.id, job.textHash, AiFill(estimate = Estimate.L)).value
        assertThat(result).isEqualTo(AiApplyResult.NOTHING_TO_FILL)
        assertThat(env.task(task.id).estimate).isEqualTo(Estimate.S)
    }

    /**
     * Property (tech plan §8.4): after undoing any automation batch, the next catch-up does not repeat it.
     * Random task sets, random activity and review days, random undo choices.
     */
    @Test
    fun `undone automation is never repeated by catch-up`() = runTest {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val local = DataEnv(settings = AppSettings(parserLanguages = setOf("ru", "uk", "en")))
            try {
                val texts =
                    listOf("сегодня отчёт", "завтра звонок", "на неделе встреча", "когда-нибудь курс", "идея", "в пт до 12:00 сдать")
                val ids = (0 until random.nextInt(3, 8)).map { local.add(texts[random.nextInt(texts.size)] + " $it").id }
                local.maintenance.catchUp()
                repeat(random.nextInt(3, 25)) {
                    if (random.nextBoolean()) local.maintenance.recordActivity()
                    if (random.nextInt(4) == 0) local.plans.accept()
                    if (random.nextInt(3) == 0) {
                        val session = local.review.startSession(ReviewKind.RELEVANCE)
                        ids.forEach { id -> if (local.task(id).inReview) local.review.cardShown(session, EntityType.TASK, id) }
                    }
                    if (random.nextInt(6) == 0) {
                        val id = ids[random.nextInt(ids.size)]
                        if (local.task(id).status.isActive) local.tasks.postpone(id, PostponeOption.Tomorrow)
                    }
                    local.advanceDays(1)
                    local.maintenance.catchUp()
                }
                val batches = local.allEvents().filter { it.actor == Actor.RULE && !it.isUndone }.map { it.batchId }.distinct()
                if (batches.isEmpty()) return@repeat
                val chosen = batches.shuffled(random).take(random.nextInt(1, batches.size + 1))
                val undoneKeys = chosen.flatMap { batch ->
                    local.allEvents().filter { it.batchId == batch }.map { it.entityId to it.reason?.code }
                }.toSet()
                chosen.forEach { local.undo.undoBatch(it) }
                val before = local.allEvents().size
                local.maintenance.catchUp()
                val repeated = local.allEvents().drop(before).filter { (it.entityId to it.reason?.code) in undoneKeys }
                assertThat(repeated).isEmpty()
            } finally {
                local.close()
            }
        }
    }

    private companion object {
        const val SEEDS = 12
    }
}
