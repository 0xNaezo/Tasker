package app.tasker.core.backup

import app.tasker.core.data.di.IoDispatcher
import app.tasker.core.data.export.DataExporter
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** A backup that can bring the data back, with what it holds. */
data class RestoreCandidate(
    val file: File,
    val summary: ExportSummary,
) {
    /** The logical day whose data the backup holds: "Restore data from <day>?". */
    val day: LocalDate get() = summary.day

    val taskCount: Int get() = summary.tasks
}

/**
 * Restore on first start (tech plan §16): when the database is empty and a backup is found — a daily copy, or
 * `latest.json.gz` that Android Auto Backup put back after a reinstall or a move to a new phone — the UI asks once
 * "Restore data from <day>?" and restores with one tap.
 *
 * After a restore the data is as of the backup: the app should run its catch-up pass (`MaintenanceRunner.catchUp`) and
 * reschedule reminders, as it does on every start.
 */
@Singleton
class RestoreService @Inject internal constructor(
    private val exporter: DataExporter,
    private val store: BackupStore,
    private val importer: Importer,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * The newest readable backup with data, or null when the database is not empty or there is nothing to restore.
     * Reads the backup to report its day and size; imports nothing.
     */
    suspend fun findRestorable(): RestoreCandidate? = withContext(io) {
        if (!exporter.isEmpty()) return@withContext null
        candidates().firstNotNullOfOrNull(::inspect)
    }

    /**
     * Restores [candidate]. Refuses with [ImportResult.NotEmpty] if data appeared in the meantime, unless [replace] is
     * true; replaced data is saved as today's backup first.
     */
    suspend fun restore(candidate: RestoreCandidate, replace: Boolean = false): ImportResult = withContext(io) {
        val bundle = try {
            importer.decode(store.read(candidate.file))
        } catch (e: ImportFileException) {
            return@withContext ImportResult.Invalid(e.problem)
        }
        importer.import(bundle, replace)
    }

    /**
     * Backup files, most recently written first: `latest.json.gz` and the newest dated copy are written together,
     * and after an Auto Backup restore `latest.json.gz` is the only one.
     */
    private fun candidates(): List<File> {
        val files = store.dated().map { it.file } + listOfNotNull(store.latest.takeIf { it.isFile })
        return files.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name != BackupStore.LATEST_FILE })
    }

    private fun inspect(file: File): RestoreCandidate? {
        val summary = try {
            importer.summary(importer.decode(store.read(file)))
        } catch (ignored: ImportFileException) {
            // Damaged or unreadable: an older copy may still be good.
            return null
        }
        return RestoreCandidate(file, summary).takeIf { summary.tasks > 0 || summary.projects > 0 }
    }
}
