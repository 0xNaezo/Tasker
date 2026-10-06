package app.tasker.core.data

import app.tasker.core.data.command.CaptureRequest
import app.tasker.core.data.command.SnapshotInput
import app.tasker.core.data.export.ExportBundle
import app.tasker.core.data.export.ExportFormat
import app.tasker.core.data.export.ExportTables
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.EntityType
import app.tasker.core.model.ReviewKind
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExportTest {
    private val env = DataEnv()
    private val target = DataEnv()

    @After
    fun tearDown() {
        env.close()
        target.close()
    }

    private suspend fun fill() {
        val report = env.capture.capture(CaptureRequest("пт до 18:00 сдать отчёт #client M @работа", CaptureChannel.BAR)).value.task
        val link = env.add("почитать https://example.com/a")
        val done = env.add("уже сделал зарядку")
        val paused = env.add("сегодня написать статью")
        env.tasks.start(paused.id)
        env.tasks.pause(paused.id, SnapshotInput("остановился на введении"))
        env.plans.accept()
        env.tasks.postpone(report.id, PostponeOption.Tomorrow)
        env.tasks.archive(link.id)
        env.maintenance.recordActivity()
        val session = env.review.startSession(ReviewKind.RELEVANCE)
        env.review.cardShown(session, EntityType.TASK, done.id)
        env.advanceDays(10)
        env.maintenance.catchUp()
    }

    @Test
    fun `export then import into an empty database gives the same data`() = runTest {
        fill()
        val first = env.exporter.export()
        val text = env.exporter.encode(first)

        assertThat(target.exporter.isEmpty()).isTrue()
        target.exporter.import(target.exporter.decode(text))
        val second = target.exporter.export()

        assertThat(second.tables).isEqualTo(first.tables)
        assertThat(second.settings).isEqualTo(first.settings)
        assertThat(first.tables.task).hasSize(4)
        assertThat(first.tables.event).isNotEmpty()
        assertThat(target.search.searchOnce("отчеты").map { it.title }).containsExactly("сдать отчёт")
        assertThat(target.search.searchOnce("введение")).hasSize(1)
    }

    @Test
    fun `every database column is exported or explicitly a service table`() {
        val schema = File("../database/schemas/app.tasker.core.database.TaskerDatabase/1.json")
        assertThat(schema.exists()).isTrue()
        val entities = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
            .getValue("entities").jsonArray
        val tables = ExportTables.serializer().descriptor
        val missing = ArrayList<String>()
        for (entity in entities) {
            val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
            if (table in ExportFormat.SERVICE_TABLES) continue
            val index = tables.elementNames.indexOf(table)
            if (index < 0) {
                missing += table
                continue
            }
            val rowDescriptor = tables.getElementDescriptor(index).rowDescriptor()
            entity.jsonObject.getValue("fields").jsonArray
                .map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
                .filter { it !in rowDescriptor.elementNames }
                .forEach { missing += "$table.$it" }
        }
        assertThat(missing).isEmpty()
    }

    @Test
    fun `an export from a newer format version is rejected`() {
        val bundle =
            ExportBundle(
                exportedAt = "2026-10-06T07:00:00Z",
                dbVersion = 1,
                settings = app.tasker.core.model.AppSettings(),
                tables = ExportTables(),
            )
        val text = env.exporter.encode(bundle.copy(formatVersion = ExportFormat.VERSION + 1))
        val error = runCatching { env.exporter.decode(text) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun SerialDescriptor.rowDescriptor(): SerialDescriptor = getElementDescriptor(0)
}
