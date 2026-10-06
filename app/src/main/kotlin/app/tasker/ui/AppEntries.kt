package app.tasker.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import app.tasker.R
import app.tasker.core.model.ProjectId
import app.tasker.core.model.TaskId
import app.tasker.core.ui.component.EmptyState
import app.tasker.feature.capture.CaptureIntents
import app.tasker.feature.done.DoneKey
import app.tasker.feature.done.DoneScreen
import app.tasker.feature.inbox.InboxKey
import app.tasker.feature.inbox.InboxScreen
import app.tasker.feature.inbox.InboxTriageKey
import app.tasker.feature.inbox.InboxTriageScreen
import app.tasker.feature.journal.JournalKey
import app.tasker.feature.journal.JournalScreen
import app.tasker.feature.review.ReviewKey
import app.tasker.feature.review.ReviewScreen
import app.tasker.feature.search.ArchiveKey
import app.tasker.feature.search.ArchiveScreen
import app.tasker.feature.search.SearchKey
import app.tasker.feature.search.SearchScreen
import app.tasker.feature.settings.AiSettingsKey
import app.tasker.feature.settings.AiSettingsScreen
import app.tasker.feature.settings.SettingsKey
import app.tasker.feature.settings.SettingsScreen
import app.tasker.feature.settings.TileRequest
import app.tasker.feature.task.TaskKey
import app.tasker.feature.task.TaskScreen
import app.tasker.feature.tasks.ProjectKey
import app.tasker.feature.tasks.ProjectScreen
import app.tasker.feature.tasks.TasksKey
import app.tasker.feature.tasks.TasksScreen
import app.tasker.feature.today.PlanKey
import app.tasker.feature.today.PlanScreen
import app.tasker.feature.today.TodayKey
import app.tasker.feature.today.TodayScreen
import app.tasker.navigation.AppNavigator
import app.tasker.navigation.Tab

/**
 * Screens of one tab's back stack. Features know nothing about each other: every cross-screen jump is wired here
 * (tech plan §6). Content keys carry the tab, so the same screen opened from two tabs keeps separate state.
 * In Inbox and Tasks the lists and the task card carry list–detail roles: on a wide window they share the screen.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun tabEntries(
    tab: Tab,
    navigator: AppNavigator,
    versionName: String,
    twoPane: () -> Boolean,
): (NavKey) -> NavEntry<NavKey> = entryProvider {
    val contentKey: (NavKey) -> Any = { "${tab.name}/$it" }
    val listPane = if (tab.listDetail) ListDetailSceneStrategy.listPane(tab.name) { TaskPlaceholder() } else emptyMap()
    val detailPane = if (tab.listDetail) ListDetailSceneStrategy.detailPane(tab.name) else emptyMap()
    val openTask: (TaskId) -> Unit = { navigator.openDetail(TaskKey(it), besideList = tab.listDetail && twoPane()) }
    val openProject: (ProjectId) -> Unit = { navigator.navigate(ProjectKey(it)) }
    val openTriage: () -> Unit = { navigator.open(Tab.INBOX, listOf(InboxTriageKey)) }
    val back: () -> Unit = navigator::back

    entry<TodayKey>(clazzContentKey = contentKey) {
        TabRoot(Tab.TODAY, navigator) { padding ->
            TodayScreen(
                onOpenTask = openTask,
                onOpenPlan = { navigator.navigate(PlanKey) },
                onOpenReview = { navigator.navigate(ReviewKey) },
                onOpenInbox = openTriage,
                onOpenAiSettings = { navigator.navigate(AiSettingsKey) },
                contentPadding = padding,
            )
        }
    }
    entry<InboxKey>(clazzContentKey = contentKey, metadata = listPane) {
        TabRoot(Tab.INBOX, navigator) { padding ->
            InboxScreen(onOpenTask = openTask, onOpenTriage = { navigator.navigate(InboxTriageKey) }, contentPadding = padding)
        }
    }
    entry<TasksKey>(clazzContentKey = contentKey, metadata = listPane) {
        TabRoot(Tab.TASKS, navigator) { padding ->
            TasksScreen(onOpenTask = openTask, onOpenProject = openProject, contentPadding = padding)
        }
    }
    entry<DoneKey>(clazzContentKey = contentKey) {
        TabRoot(Tab.DONE, navigator) { padding -> DoneScreen(onOpenTask = openTask, contentPadding = padding) }
    }
    entry<PlanKey>(clazzContentKey = contentKey) {
        PlanScreen(onBack = back, onOpenTask = openTask, onOpenReview = { navigator.navigate(ReviewKey) }, onOpenInbox = openTriage)
    }
    entry<InboxTriageKey>(clazzContentKey = contentKey) { InboxTriageScreen(onBack = back, onOpenTask = openTask) }
    entry<ProjectKey>(clazzContentKey = contentKey, metadata = listPane) { key ->
        ProjectScreen(projectId = key.projectId, onBack = back, onOpenTask = openTask)
    }
    entry<TaskKey>(clazzContentKey = contentKey, metadata = detailPane) { key ->
        TaskScreen(taskId = key.taskId, onBack = back, onOpenProject = openProject)
    }
    entry<ReviewKey>(clazzContentKey = contentKey) { ReviewScreen(onBack = back, onOpenTask = openTask, onOpenProject = openProject) }
    entry<SearchKey>(clazzContentKey = contentKey) {
        SearchScreen(onBack = back, onOpenTask = openTask, onOpenArchive = { navigator.navigate(ArchiveKey) })
    }
    entry<ArchiveKey>(clazzContentKey = contentKey) { ArchiveScreen(onBack = back, onOpenTask = openTask) }
    entry<JournalKey>(clazzContentKey = contentKey) { JournalScreen(onBack = back, onOpenTask = openTask) }
    entry<SettingsKey>(clazzContentKey = contentKey) {
        val context = LocalContext.current
        SettingsScreen(
            onBack = back,
            onOpenAi = { navigator.navigate(AiSettingsKey) },
            versionName = versionName,
            tile = remember(context) { tileRequest(context) },
        )
    }
    entry<AiSettingsKey>(clazzContentKey = contentKey) { AiSettingsScreen(onBack = back) }
}

/** The capture tile is offered from settings: by the system dialog on Android 13+, by instructions before (CAP-6). */
private fun tileRequest(context: Context): TileRequest = if (CaptureIntents.canRequestTile) {
    TileRequest.System { onResult -> CaptureIntents.requestAddTile(context, onResult) }
} else {
    TileRequest.Manual
}

/** The card pane before a task is chosen. */
@Composable
private fun TaskPlaceholder() {
    Surface(Modifier.fillMaxSize()) {
        EmptyState(title = stringResource(R.string.app_pick_task), icon = Icons.Outlined.TaskAlt)
    }
}
