package app.tasker.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tasker.core.data.command.CaptureRequest
import app.tasker.core.data.command.SnapshotInput
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.EntityType
import app.tasker.core.model.ReviewKind
import java.io.File
import kotlinx.coroutines.Dispatchers

/** The backup module wired by hand over a [DataEnv]; backups live in [root]/backups. */
class BackupEnv(
    root: File,
    val data: DataEnv = DataEnv(),
    folder: BackupFolder = DocumentBackupFolder(ApplicationProvider.getApplicationContext()),
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val lock = DataLock()
    val store = BackupStore(File(root, BackupStore.DIR_NAME))
    val backups = BackupService(data.exporter, store, folder, data.settings, data.clock, lock, Dispatchers.IO)
    internal val importer = Importer(data.exporter, backups, data.clock, lock)
    val export = ExportService(context, data.exporter, importer, data.clock, lock, Dispatchers.IO)
    val restore = RestoreService(data.exporter, store, importer, Dispatchers.IO)

    fun close() = data.close()
}

/** A realistic data set: a project, tags, a deadline, a paused task with a snapshot, a link, done and archived tasks. */
suspend fun DataEnv.fill() {
    val report = capture.capture(CaptureRequest("пт до 18:00 сдать отчёт #client M @работа", CaptureChannel.BAR)).value.task
    val link = add("почитать https://example.com/a")
    val done = add("уже сделал зарядку")
    val paused = add("сегодня написать статью")
    tasks.start(paused.id)
    tasks.pause(paused.id, SnapshotInput("остановился на введении"))
    plans.accept()
    tasks.postpone(report.id, PostponeOption.Tomorrow)
    tasks.archive(link.id)
    maintenance.recordActivity()
    val session = review.startSession(ReviewKind.RELEVANCE)
    review.cardShown(session, EntityType.TASK, done.id)
    advanceDays(10)
    maintenance.catchUp()
}
