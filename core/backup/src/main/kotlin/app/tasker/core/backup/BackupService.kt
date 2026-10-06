package app.tasker.core.backup

import app.tasker.core.data.di.IoDispatcher
import app.tasker.core.data.export.DataExporter
import app.tasker.core.data.export.ExportBundle
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.time.DayClock
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Result of one backup run. */
sealed interface BackupResult {
    /** The database has no tasks and no projects. Nothing was written, so the last good copy is kept. */
    data object SkippedEmpty : BackupResult

    /**
     * @property file the dated backup that was written; `latest.json.gz` now holds the same bytes.
     * @property removed older backups deleted by the rotation.
     */
    data class Written(
        val file: File,
        val day: LocalDate,
        val removed: List<File>,
        val folder: FolderCopy,
    ) : BackupResult
}

/** Outcome of the extra copy into the user-chosen folder. Never fails the backup itself. */
sealed interface FolderCopy {
    /** No folder is set in the settings. */
    data object NotSet : FolderCopy

    data object Copied : FolderCopy

    /** The folder is unreachable or no longer granted (e.g. settings restored on a new phone); the UI may ask again. */
    data class Failed(val error: Exception) : FolderCopy
}

/**
 * Daily local backup (tech plan §16, NFR "Сохранность"): gzip of export.json in `files/backups/`, rotated to 7 daily and
 * 4 weekly copies, `latest.json.gz` refreshed for Auto Backup, and a copy in the user-chosen folder when one is set.
 * Runs from [BackupWorker]; the settings screen may call [backUp] directly ("Back up now", right after picking a folder).
 */
@Singleton
class BackupService @Inject constructor(
    private val exporter: DataExporter,
    private val store: BackupStore,
    private val folder: BackupFolder,
    private val settings: SettingsRepository,
    private val clock: DayClock,
    private val lock: DataLock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * Writes the backup of the current logical day (a second run on the same day replaces it) and rotates old copies.
     * An empty database is never backed up: after a reinstall it would replace the copy that the restore offer needs.
     */
    suspend fun backUp(): BackupResult = withContext(io) {
        val saved = lock.withLock { saveToday() } ?: return@withContext BackupResult.SkippedEmpty
        // Outside the lock, so a slow storage provider never holds up an import. Backups are replaced by renaming, so
        // the copy reads whole bytes even if a newer backup lands meanwhile.
        BackupResult.Written(saved.file, saved.day, saved.removed, copyToFolder(saved.file, saved.day))
    }

    /** Dated backups in the private folder, newest first; e.g. "Last backup: <day>" in the settings. */
    suspend fun backups(): List<BackupFile> = withContext(io) { store.dated() }

    /** Saves [bundle], the data about to be replaced by an import, as today's backup. Caller holds [DataLock]. */
    internal fun writeSafetyCopy(bundle: ExportBundle) {
        write(bundle, clock.today())
    }

    private suspend fun saveToday(): Saved? {
        if (exporter.isEmpty()) return null
        val bundle = exporter.export()
        // The check above and the export are two reads; only the export is one transaction, so check its rows too.
        if (bundle.tables.task.isEmpty() && bundle.tables.project.isEmpty()) return null
        val day = clock.today()
        val file = write(bundle, day)
        return Saved(file, day, store.rotate(day))
    }

    private fun write(bundle: ExportBundle, day: LocalDate): File {
        keepRestoredLatest(day)
        return store.write(day, ExportFiles.gzip(exporter.encode(bundle)))
    }

    /**
     * After a reinstall, Auto Backup restores only `latest.json.gz`. If the user declined the restore offer and started
     * anew, that copy is kept under a dated name before it is overwritten, so the rotation ages it out like any other.
     */
    private fun keepRestoredLatest(today: LocalDate) {
        if (!store.latest.isFile || store.dated().isNotEmpty()) return
        val day = try {
            clock.logicalDay(Instant.parse(exporter.decode(store.read(store.latest)).exportedAt))
        } catch (ignored: IOException) {
            return
        } catch (ignored: IllegalArgumentException) {
            return
        } catch (ignored: DateTimeException) {
            return
        }
        store.keepLatestAs(minOf(day, today.minusDays(1)))
    }

    private suspend fun copyToFolder(file: File, day: LocalDate): FolderCopy {
        val treeUri = settings.current().backupTreeUri ?: return FolderCopy.NotSet
        return try {
            folder.store(treeUri, file, day)
            FolderCopy.Copied
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage providers throw whatever they like (SecurityException, IllegalArgumentException, IOException).
            FolderCopy.Failed(e)
        }
    }

    private class Saved(val file: File, val day: LocalDate, val removed: List<File>)
}
