package app.tasker.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.tasker.core.database.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("SELECT * FROM project WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM project WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM project WHERE status = 'ACTIVE' AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun activeByName(name: String): ProjectEntity?

    @Query("SELECT * FROM project WHERE name = :name COLLATE NOCASE ORDER BY status = 'ACTIVE' DESC LIMIT 1")
    suspend fun byName(name: String): ProjectEntity?

    @Query("SELECT * FROM project WHERE status = 'ACTIVE' ORDER BY position, created_at")
    fun observeActive(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM project WHERE status = 'ACTIVE'")
    suspend fun active(): List<ProjectEntity>

    @Query("SELECT * FROM project ORDER BY status, position, created_at")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM project")
    suspend fun all(): List<ProjectEntity>

    @Query("SELECT name FROM project WHERE status = 'ACTIVE'")
    suspend fun activeNames(): List<String>

    @Query("SELECT MAX(position) FROM project")
    suspend fun maxPosition(): Long?

    @Query("UPDATE project SET last_activity_at = MAX(last_activity_at, :at) WHERE id = :id")
    suspend fun markActivity(id: String, at: Long)
}
