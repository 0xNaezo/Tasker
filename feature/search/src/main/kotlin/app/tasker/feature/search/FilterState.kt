package app.tasker.feature.search

import androidx.compose.runtime.Immutable
import app.tasker.core.data.search.HorizonFilter
import app.tasker.core.data.search.SearchFilters
import app.tasker.core.model.Bucket
import app.tasker.core.model.ProjectId
import app.tasker.core.model.SourceKind
import app.tasker.core.model.TaskStatus

/** Status filter (SRC-2). "Active" covers open, in progress and paused tasks. */
enum class StatusChoice(val statuses: Set<TaskStatus>) {
    ACTIVE(setOf(TaskStatus.OPEN, TaskStatus.IN_PROGRESS, TaskStatus.PAUSED)),
    OPEN(setOf(TaskStatus.OPEN)),
    IN_PROGRESS(setOf(TaskStatus.IN_PROGRESS)),
    PAUSED(setOf(TaskStatus.PAUSED)),
    DONE(setOf(TaskStatus.DONE)),
    ARCHIVED(setOf(TaskStatus.ARCHIVED)),
}

/** Horizon filter (SRC-2): the Inbox or one of the buckets. */
enum class HorizonChoice(val filter: HorizonFilter) {
    INBOX(HorizonFilter.Inbox),
    TODAY(HorizonFilter.In(Bucket.TODAY)),
    WEEK(HorizonFilter.In(Bucket.WEEK)),
    SOMEDAY(HorizonFilter.In(Bucket.SOMEDAY)),
}

/** Deadline filter (SRC-2): with or without a deadline. */
enum class DeadlineChoice(val hasDeadline: Boolean) {
    WITH(true),
    WITHOUT(false),
}

/**
 * The filter chips of the search screen (SRC-2): status, horizon, project, tag, source type and deadline — every
 * one optional. Maps one to one onto [SearchFilters]; the archive stays in the results unless the status excludes it.
 */
@Immutable
data class FilterState(
    val status: StatusChoice? = null,
    val horizon: HorizonChoice? = null,
    val projectId: ProjectId? = null,
    val tag: String? = null,
    val source: SourceKind? = null,
    val deadline: DeadlineChoice? = null,
) {
    val isEmpty: Boolean get() = this == FilterState()

    fun toSearchFilters(): SearchFilters = SearchFilters(
        statuses = status?.statuses.orEmpty(),
        horizon = horizon?.filter,
        projectId = projectId,
        tag = tag,
        sourceKind = source,
        hasDeadline = deadline?.hasDeadline,
    )

    /** Flat string form for `SavedStateHandle`: the filters survive process death together with the query. */
    fun toSaved(): Map<String, String?> = mapOf(
        KEY_STATUS to status?.name,
        KEY_HORIZON to horizon?.name,
        KEY_PROJECT to projectId,
        KEY_TAG to tag,
        KEY_SOURCE to source?.name,
        KEY_DEADLINE to deadline?.name,
    )

    companion object {
        private const val KEY_STATUS = "filter_status"
        private const val KEY_HORIZON = "filter_horizon"
        private const val KEY_PROJECT = "filter_project"
        private const val KEY_TAG = "filter_tag"
        private const val KEY_SOURCE = "filter_source"
        private const val KEY_DEADLINE = "filter_deadline"

        /** Reverse of [toSaved]; unknown values (e.g. from an older version) are dropped. */
        fun fromSaved(read: (String) -> String?): FilterState = FilterState(
            status = read(KEY_STATUS)?.let { enumOrNull<StatusChoice>(it) },
            horizon = read(KEY_HORIZON)?.let { enumOrNull<HorizonChoice>(it) },
            projectId = read(KEY_PROJECT),
            tag = read(KEY_TAG),
            source = read(KEY_SOURCE)?.let { enumOrNull<SourceKind>(it) },
            deadline = read(KEY_DEADLINE)?.let { enumOrNull<DeadlineChoice>(it) },
        )

        private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
    }
}
