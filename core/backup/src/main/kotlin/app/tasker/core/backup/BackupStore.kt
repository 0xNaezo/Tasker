package app.tasker.core.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/** A dated backup in the private folder. */
data class BackupFile(val file: File, val day: LocalDate)

/**
 * Daily backups in the app's private storage (tech plan §16):
 * - `backups/backup-YYYY-MM-DD.json.gz` — gzip of export.json, one per logical day, rotated by [BackupRotation];
 * - `backups/latest.json.gz` — a copy of the newest one. Only this file goes to Android Auto Backup (see
 *   `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`), which keeps the cloud copy far below the 25 MB
 *   quota and never ships the live database.
 *
 * Writes are atomic: data goes to a temporary file that is synced and renamed over the target, so a crash or a full
 * disk never leaves half a backup. Callers serialize access with [DataLock].
 */
class BackupStore(val dir: File) {
    val latest: File get() = File(dir, LATEST_FILE)

    /** Dated backups, newest first. */
    fun dated(): List<BackupFile> = dir.listFiles().orEmpty()
        .mapNotNull { file -> dayOf(file.name)?.takeIf { file.isFile }?.let { BackupFile(file, it) } }
        .sortedByDescending { it.day }

    /** Writes the backup of [day] and makes it `latest.json.gz`; returns the dated file. */
    fun write(day: LocalDate, gzipped: ByteArray): File {
        ensureDir()
        val target = File(dir, fileName(day))
        writeAtomically(target, gzipped)
        writeAtomically(latest, gzipped)
        return target
    }

    /** Copies `latest.json.gz` to the dated name of [day], keeping a copy that Auto Backup restored before it is replaced. */
    fun keepLatestAs(day: LocalDate): File {
        val target = File(dir, fileName(day))
        writeAtomically(target, latest.readBytes())
        return target
    }

    /**
     * Deletes the dated backups [BackupRotation] does not keep on [today] and temporary files a killed process left
     * behind; returns the deleted backups.
     */
    fun rotate(today: LocalDate): List<File> {
        dir.listFiles { file -> file.name.endsWith(TEMP_SUFFIX) }.orEmpty().forEach { it.delete() }
        val files = dated()
        val expired = BackupRotation.expired(files.map { it.day }, today)
        return files.filter { it.day in expired && it.file.delete() }.map { it.file }
    }

    /**
     * Unpacked export.json of a backup [file].
     *
     * @throws ImportFileException when the file is missing or damaged.
     */
    fun read(file: File): String {
        val input = try {
            FileInputStream(file)
        } catch (e: FileNotFoundException) {
            throw ImportFileException(ImportProblem.UNREADABLE, "Cannot open ${file.name}", e)
        }
        return input.use { ExportFiles.readExportJson(it) }
    }

    private fun ensureDir() {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(dir, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    companion object {
        const val DIR_NAME = "backups"
        const val LATEST_FILE = "latest.json.gz"
        private const val TEMP_SUFFIX = ".tmp"
        private val DATED_NAME = Regex("""backup-(\d{4}-\d{2}-\d{2})\.json\.gz""")

        fun fileName(day: LocalDate): String = "backup-$day.json.gz"

        /** The day in a dated backup name, or null for any other file. */
        fun dayOf(name: String): LocalDate? = dateInName(DATED_NAME, name)
    }
}
