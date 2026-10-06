package app.tasker.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.tasker.core.database.dao.ContentDao
import app.tasker.core.database.dao.EventDao
import app.tasker.core.database.dao.PlanDao
import app.tasker.core.database.dao.ProjectDao
import app.tasker.core.database.dao.SearchDao
import app.tasker.core.database.dao.ServiceDao
import app.tasker.core.database.dao.TaskDao
import app.tasker.core.database.entity.ActivityDayEntity
import app.tasker.core.database.entity.ContextSnapshotEntity
import app.tasker.core.database.entity.DayPlanEntity
import app.tasker.core.database.entity.DayPlanItemEntity
import app.tasker.core.database.entity.EventEntity
import app.tasker.core.database.entity.MaintenanceStateEntity
import app.tasker.core.database.entity.NotificationLogEntity
import app.tasker.core.database.entity.PendingNotificationEntity
import app.tasker.core.database.entity.ProjectEntity
import app.tasker.core.database.entity.ReviewCardEntity
import app.tasker.core.database.entity.ReviewSessionEntity
import app.tasker.core.database.entity.SourceEntity
import app.tasker.core.database.entity.TagEntity
import app.tasker.core.database.entity.TaskEntity
import app.tasker.core.database.entity.TaskFtsEntity
import app.tasker.core.database.entity.TaskTagEntity

/**
 * Local source of truth (tech plan §7). Schemas are exported to `schemas/`; every migration gets a
 * MigrationTestHelper test and destructive migrations are never used in release builds (§7.9).
 */
@Database(
    entities = [
        TaskEntity::class,
        ProjectEntity::class,
        TagEntity::class,
        TaskTagEntity::class,
        ContextSnapshotEntity::class,
        SourceEntity::class,
        DayPlanEntity::class,
        DayPlanItemEntity::class,
        EventEntity::class,
        ReviewSessionEntity::class,
        ReviewCardEntity::class,
        ActivityDayEntity::class,
        NotificationLogEntity::class,
        PendingNotificationEntity::class,
        MaintenanceStateEntity::class,
        TaskFtsEntity::class,
    ],
    version = TaskerDatabase.VERSION,
    exportSchema = true,
)
abstract class TaskerDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    abstract fun projectDao(): ProjectDao

    abstract fun contentDao(): ContentDao

    abstract fun planDao(): PlanDao

    abstract fun eventDao(): EventDao

    abstract fun serviceDao(): ServiceDao

    abstract fun searchDao(): SearchDao

    companion object {
        const val VERSION = 1
        const val NAME = "tasker.db"
    }
}
