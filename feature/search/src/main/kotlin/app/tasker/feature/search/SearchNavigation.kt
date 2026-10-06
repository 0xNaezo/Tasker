package app.tasker.feature.search

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** "Search": full-text search with filters, the archive included and marked (SRC-1, SRC-2). */
@Serializable
data object SearchKey : NavKey

/** "Archive": archived tasks by archive date, restore in one tap, permanent deletion with confirmation (ARC-1, TTL-8). */
@Serializable
data object ArchiveKey : NavKey
