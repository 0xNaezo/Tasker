package app.tasker.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import app.tasker.R
import app.tasker.feature.done.DoneKey
import app.tasker.feature.inbox.InboxKey
import app.tasker.feature.tasks.TasksKey
import app.tasker.feature.today.TodayKey

/** Bottom navigation (§14.1). */
enum class Tab(val root: NavKey, @param:StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    TODAY(TodayKey, R.string.tab_today, Icons.Outlined.WbSunny, Icons.Filled.WbSunny),
    INBOX(InboxKey, R.string.tab_inbox, Icons.Outlined.Inbox, Icons.Filled.Inbox),
    TASKS(TasksKey, R.string.tab_tasks, Icons.AutoMirrored.Outlined.ListAlt, Icons.AutoMirrored.Filled.ListAlt),
    DONE(DoneKey, R.string.tab_done, Icons.Outlined.TaskAlt, Icons.Filled.TaskAlt),
    ;

    companion object {
        val START = TODAY
    }
}
