package app.tasker.feature.task

import androidx.navigation3.runtime.NavKey
import app.tasker.core.model.TaskId
import kotlinx.serialization.Serializable

/** Task card (§14.2). Deep link `tasker://task/{id}`. */
@Serializable
data class TaskKey(val taskId: TaskId) : NavKey
