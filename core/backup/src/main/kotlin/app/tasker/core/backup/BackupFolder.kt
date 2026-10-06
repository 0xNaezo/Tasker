package app.tasker.core.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject

/** The folder the user picked for extra backup copies (`AppSettings.backupTreeUri`, tech plan §16). */
fun interface BackupFolder {
    /**
     * Copies [backup], the backup of [day], into the folder [treeUri] and deletes copies the rotation no longer keeps.
     * Throws when the folder is gone or the permission was revoked; storage providers throw exceptions of any type, and
     * the caller reports them instead of failing the backup.
     */
    fun store(treeUri: String, backup: File, day: LocalDate)
}

/**
 * [BackupFolder] on the Storage Access Framework. The settings screen obtains the folder with
 * `ActivityResultContracts.OpenDocumentTree`, calls `ContentResolver.takePersistableUriPermission` with read and write
 * flags, and saves the URI string in `backupTreeUri`.
 *
 * Copies are named `tasker-backup-YYYY-MM-DD.json.gz`: the prefix keeps them apart from other apps' files in a shared
 * folder, and only files with exactly this name pattern are ever deleted.
 */
class DocumentBackupFolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : BackupFolder {
    override fun store(treeUri: String, backup: File, day: LocalDate) {
        val folder = DocumentFile.fromTreeUri(context, treeUri.toUri()) ?: throw IOException("Not a folder: $treeUri")
        storeIn(folder, backup, day)
    }

    /** Copies into [folder] and rotates the copies there; any DocumentFile works, a plain directory included. */
    fun storeIn(folder: DocumentFile, backup: File, day: LocalDate) {
        if (!folder.isDirectory || !folder.canWrite()) throw IOException("The backup folder is not writable")
        val name = fileName(day)
        val target = folder.findFile(name) ?: create(folder, name)
        context.contentResolver.openTruncating(target.uri).use { out -> backup.inputStream().use { it.copyTo(out) } }
        rotate(folder, day)
    }

    private fun create(folder: DocumentFile, name: String): DocumentFile {
        val file = folder.createFile(MIME_TYPE, name) ?: throw IOException("Cannot create $name")
        // Some storage providers adjust the name, e.g. append the extension of the MIME type once more; ask for ours
        // back so the rotation recognizes the copy. If renaming is not supported, the copy keeps the provider's name.
        if (file.name != name) file.renameTo(name)
        return file
    }

    private fun rotate(folder: DocumentFile, today: LocalDate) {
        val copies = folder.listFiles().mapNotNull { file -> file.name?.let(::dayOf)?.let { it to file } }
        val expired = BackupRotation.expired(copies.map { it.first }, today)
        copies.filter { it.first in expired }.forEach { it.second.delete() }
    }

    companion object {
        const val MIME_TYPE = "application/gzip"
        private val NAME = Regex("""tasker-backup-(\d{4}-\d{2}-\d{2})\.json\.gz""")

        fun fileName(day: LocalDate): String = "tasker-backup-$day.json.gz"

        /** The day in a copy's name, or null for any file this app did not write. */
        fun dayOf(name: String): LocalDate? = dateInName(NAME, name)
    }
}
