package app.tasker.navigation

import app.tasker.core.notifications.DeepLink
import app.tasker.feature.review.ReviewKey
import app.tasker.feature.task.TaskKey
import app.tasker.feature.today.PlanKey

/**
 * Follows a link from a notification, the share sheet or another app (`tasker://…`, core:notifications). A task opens
 * over the current tab, so Back returns to where the user was; the plan and the review belong to "Today".
 * [DeepLink.Postpone] is not a screen: the app shows the postpone choice over whatever is open.
 */
fun AppNavigator.follow(link: DeepLink) {
    when (link) {
        is DeepLink.Task -> navigate(TaskKey(link.taskId))
        DeepLink.Plan -> open(Tab.TODAY, listOf(PlanKey))
        DeepLink.Review -> open(Tab.TODAY, listOf(ReviewKey))
        is DeepLink.Postpone -> Unit
    }
}
