package app.tasker.core.data.search

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import app.tasker.core.data.mapper.toModel
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.model.Bucket
import app.tasker.core.model.ProjectId
import app.tasker.core.model.SourceKind
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where a task is: one of the buckets, the Inbox, or any (filter SRC-2). */
sealed interface HorizonFilter {
    data class In(val bucket: Bucket) : HorizonFilter

    data object Inbox : HorizonFilter
}

/** Search filters (SRC-2); all optional. The archive is included by default and marked in results. */
data class SearchFilters(
    val statuses: Set<TaskStatus> = emptySet(),
    val horizon: HorizonFilter? = null,
    val projectId: ProjectId? = null,
    val tag: String? = null,
    val sourceKind: SourceKind? = null,
    val hasDeadline: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = statuses.isEmpty() && horizon == null && projectId == null && tag == null && sourceKind == null && hasDeadline == null
}

/** A result with the ranges of the title and note that match the query, computed with the same stemmer (§15). */
data class SearchHit(val task: Task, val titleHighlights: List<IntRange>, val noteHighlights: List<IntRange>) {
    val isArchived: Boolean get() = task.status == TaskStatus.ARCHIVED
}

/** Full-text search with filters over the FTS4 index (SRC-1, SRC-2, tech plan §15). */
@Singleton
class SearchRepository @Inject constructor(private val db: TaskerDatabase) {
    fun search(query: String, filters: SearchFilters = SearchFilters()): Flow<PagingData<SearchHit>> {
        val sql = buildQuery(query, filters) ?: return kotlinx.coroutines.flow.flowOf(PagingData.empty())
        return Pager(PagingConfig(pageSize = PAGE_SIZE)) { db.searchDao().searchPaging(sql) }.flow.map { page ->
            page.map { row ->
                val task = row.toModel()
                SearchHit(
                    task = task,
                    titleHighlights = SearchNormalizer.highlights(task.title, query),
                    noteHighlights = task.note?.let { SearchNormalizer.highlights(it, query) }.orEmpty(),
                )
            }
        }
    }

    /** Same query without paging, for tests and small lists. */
    suspend fun searchOnce(query: String, filters: SearchFilters = SearchFilters(), limit: Int = PAGE_SIZE): List<Task> {
        val sql = buildQuery(query, filters, limit) ?: return emptyList()
        return db.searchDao().search(sql).map { it.toModel() }
    }

    /** SQL assembled from a fixed set of conditions with bound arguments; null when there is nothing to search for. */
    internal fun buildQuery(query: String, filters: SearchFilters, limit: Int? = null): SimpleSQLiteQuery? {
        val match = SearchNormalizer.matchQuery(query)
        if (match == null && filters.isEmpty) return null
        val where = ArrayList<String>()
        val args = ArrayList<Any>()
        if (match != null) {
            where += "id IN (SELECT task_id FROM task_fts WHERE task_fts MATCH ?)"
            args += match
        }
        if (filters.statuses.isNotEmpty()) {
            where += "status IN (${filters.statuses.joinToString(",") { "?" }})"
            args.addAll(filters.statuses.map { it.name })
        }
        when (val horizon = filters.horizon) {
            is HorizonFilter.In -> {
                where += "bucket = ?"
                args += horizon.bucket.name
            }
            HorizonFilter.Inbox -> where += "bucket IS NULL AND project_id IS NULL"
            null -> Unit
        }
        filters.projectId?.let {
            where += "project_id = ?"
            args += it
        }
        filters.tag?.let {
            where += "id IN (SELECT tt.task_id FROM task_tag tt JOIN tag t ON t.id = tt.tag_id WHERE t.name_norm = ?)"
            args += app.tasker.core.data.command.Tx.normalizeTag(it)
        }
        filters.sourceKind?.let {
            where += "id IN (SELECT task_id FROM source WHERE kind = ?)"
            args += it.name
        }
        when (filters.hasDeadline) {
            true -> where += "deadline_date IS NOT NULL"
            false -> where += "deadline_date IS NULL"
            null -> Unit
        }
        val sql = buildString {
            append("SELECT * FROM task")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY status = 'ARCHIVED', updated_at DESC")
            if (limit != null) append(" LIMIT ").append(limit)
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
