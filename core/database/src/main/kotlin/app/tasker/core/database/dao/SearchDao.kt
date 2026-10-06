package app.tasker.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import app.tasker.core.database.entity.SourceEntity
import app.tasker.core.database.entity.TagEntity
import app.tasker.core.database.entity.TaskEntity
import app.tasker.core.database.entity.TaskFtsEntity
import app.tasker.core.database.entity.TaskTagEntity
import app.tasker.core.database.entity.TaskWithTags

@Dao
interface SearchDao {
    @Insert
    suspend fun insert(row: TaskFtsEntity)

    @Query("DELETE FROM task_fts WHERE task_id = :taskId")
    suspend fun delete(taskId: String)

    @Transaction
    suspend fun replace(row: TaskFtsEntity) {
        delete(row.taskId)
        insert(row)
    }

    @Query("SELECT task_id FROM task_fts WHERE task_fts MATCH :query")
    suspend fun match(query: String): List<String>

    /** Filtered search (SRC-2): the SQL is assembled from a fixed set of conditions with bound arguments. */
    @Transaction
    @RawQuery(observedEntities = [TaskEntity::class, TaskFtsEntity::class])
    suspend fun search(query: SupportSQLiteQuery): List<TaskWithTags>

    /** Paged variant of [search]; the query must not contain LIMIT or OFFSET. */
    @Transaction
    @RawQuery(observedEntities = [TaskEntity::class, TaskFtsEntity::class, TaskTagEntity::class, TagEntity::class, SourceEntity::class])
    fun searchPaging(query: SupportSQLiteQuery): PagingSource<Int, TaskWithTags>

    @Query("DELETE FROM task_fts")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM task_fts")
    suspend fun count(): Int
}
