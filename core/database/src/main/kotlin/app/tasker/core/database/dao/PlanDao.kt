package app.tasker.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.tasker.core.database.entity.DayPlanEntity
import app.tasker.core.database.entity.DayPlanItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query("SELECT * FROM day_plan WHERE date = :date")
    suspend fun plan(date: Long): DayPlanEntity?

    @Query("SELECT * FROM day_plan WHERE date = :date")
    fun observePlan(date: Long): Flow<DayPlanEntity?>

    @Query("SELECT * FROM day_plan_item WHERE date = :date ORDER BY position")
    suspend fun items(date: Long): List<DayPlanItemEntity>

    @Query("SELECT * FROM day_plan_item WHERE date = :date ORDER BY position")
    fun observeItems(date: Long): Flow<List<DayPlanItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(plan: DayPlanEntity)

    @Update
    suspend fun updatePlan(plan: DayPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<DayPlanItemEntity>)

    @Query("DELETE FROM day_plan_item WHERE date = :date")
    suspend fun clearItems(date: Long)

    @Transaction
    suspend fun replaceItems(date: Long, items: List<DayPlanItemEntity>) {
        clearItems(date)
        upsertItems(items)
    }

    @Query("UPDATE day_plan_item SET outcome = :outcome WHERE date = :date AND task_id = :taskId")
    suspend fun setOutcome(date: Long, taskId: String, outcome: String)

    @Query("SELECT * FROM day_plan_item WHERE date = :date AND task_id = :taskId")
    suspend fun item(date: Long, taskId: String): DayPlanItemEntity?

    @Query("SELECT * FROM day_plan WHERE date >= :from AND date <= :to ORDER BY date")
    suspend fun plansBetween(from: Long, to: Long): List<DayPlanEntity>

    @Query("SELECT * FROM day_plan_item WHERE date >= :from AND date <= :to")
    suspend fun itemsBetween(from: Long, to: Long): List<DayPlanItemEntity>

    @Query("SELECT * FROM day_plan")
    suspend fun allPlans(): List<DayPlanEntity>

    @Query("SELECT * FROM day_plan_item")
    suspend fun allItems(): List<DayPlanItemEntity>
}
