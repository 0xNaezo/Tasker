package app.tasker.ui

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import app.tasker.core.model.ProjectId
import app.tasker.core.model.TaskId
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
 */
internal fun tabEntries(tab: Tab, navigator: AppNavigator, versionName: String): (NavKey) -> NavEntry<NavKey> = entryProvider {
    val contentKey: (NavKey) -> Any = { "${tab.name}/$it" }
    val openTask: (TaskId) -> Unit = { navigator.navigate(TaskKey(it)) }
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
    entry<InboxKey>(clazzContentKey = contentKey) {
        TabRoot(Tab.INBOX, navigator) { padding ->
            InboxScreen(onOpenTask = openTask, onOpenTriage = { navigator.navigate(InboxTriageKey) }, contentPadding = padding)
        }
    }
    entry<TasksKey>(clazzContentKey = contentKey) {
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
    entry<ProjectKey>(clazzContentKey = contentKey) { key ->
        ProjectScreen(projectId = key.projectId, onBack = back, onOpenTask = openTask)
    }
    entry<TaskKey>(clazzContentKey = contentKey) { key -> TaskScreen(taskId = key.taskId, onBack = back, onOpenProject = openProject) }
    entry<ReviewKey>(clazzContentKey = contentKey) { ReviewScreen(onBack = back, onOpenTask = openTask, onOpenProject = openProject) }
    entry<SearchKey>(clazzContentKey = contentKey) {
        SearchScreen(onBack = back, onOpenTask = openTask, onOpenArchive = { navigator.navigate(ArchiveKey) })
    }
    entry<ArchiveKey>(clazzContentKey = contentKey) { ArchiveScreen(onBack = back, onOpenTask = openTask) }
    entry<JournalKey>(clazzContentKey = contentKey) { JournalScreen(onBack = back, onOpenTask = openTask) }
    entry<SettingsKey>(clazzContentKey = contentKey) {
        SettingsScreen(onBack = back, onOpenAi = { navigator.navigate(AiSettingsKey) }, versionName = versionName)
    }
    entry<AiSettingsKey>(clazzContentKey = contentKey) { AiSettingsScreen(onBack = back) }
}
