package app.tasker.navigation

import androidx.navigation3.runtime.NavKey
import app.tasker.feature.inbox.InboxKey
import app.tasker.feature.task.TaskKey
import app.tasker.feature.tasks.ProjectKey
import app.tasker.feature.tasks.TasksKey

/** Screens shown side by side on wide windows (§14.1): a list of tasks and the task card. */
internal object ListDetail {
    fun isList(key: NavKey): Boolean = key is InboxKey || key is TasksKey || key is ProjectKey

    fun isDetail(key: NavKey): Boolean = key is TaskKey

    fun isPane(key: NavKey): Boolean = isList(key) || isDetail(key)
}
