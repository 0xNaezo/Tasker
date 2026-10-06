package app.tasker.core.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * Opens [uri] for writing from the start. Mode "wt" truncates, so no tail of a longer old file survives (a ZIP with a
 * stale tail is unreadable); a few storage providers know only "w", which is fine for the new, empty documents that
 * `CreateDocument` and `DocumentFile.createFile` return.
 */
internal fun ContentResolver.openTruncating(uri: Uri): OutputStream {
    val stream = try {
        openOutputStream(uri, "wt")
    } catch (e: IllegalArgumentException) {
        openOutputStream(uri, "w")
    }
    return stream ?: throw FileNotFoundException("Cannot write to $uri")
}
