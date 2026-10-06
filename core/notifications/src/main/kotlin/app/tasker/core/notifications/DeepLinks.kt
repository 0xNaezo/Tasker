package app.tasker.core.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri

/** A screen the app opens from a notification or another app. */
sealed interface DeepLink {
    data class Task(val taskId: String) : DeepLink

    /** The compact postpone choice for a task: tomorrow, week, someday, a date (EXC-2, NTF-5). */
    data class Postpone(val taskId: String) : DeepLink

    data object Plan : DeepLink

    data object Review : DeepLink
}

/**
 * Deep links of the app: `tasker://task/{id}`, `tasker://postpone/{id}`, `tasker://plan`, `tasker://review`.
 * The app module registers an intent filter for the `tasker` scheme on its activity and routes with [parse].
 */
object DeepLinks {
    const val SCHEME = "tasker"
    private const val TASK = "task"
    private const val POSTPONE = "postpone"
    private const val PLAN = "plan"
    private const val REVIEW = "review"

    fun task(taskId: String): Uri = uri(TASK, taskId)

    fun postpone(taskId: String): Uri = uri(POSTPONE, taskId)

    fun plan(): Uri = uri(PLAN)

    fun review(): Uri = uri(REVIEW)

    fun uriOf(link: DeepLink): Uri = when (link) {
        is DeepLink.Task -> task(link.taskId)
        is DeepLink.Postpone -> postpone(link.taskId)
        DeepLink.Plan -> plan()
        DeepLink.Review -> review()
    }

    fun parse(uri: Uri?): DeepLink? {
        if (uri == null || uri.scheme != SCHEME) return null
        val id = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        return when (uri.host) {
            TASK -> id?.let(DeepLink::Task)
            POSTPONE -> id?.let(DeepLink::Postpone)
            PLAN -> DeepLink.Plan
            REVIEW -> DeepLink.Review
            else -> null
        }
    }

    /**
     * An intent that opens [uri] in this app only. Notifications start activities directly through such intents:
     * since Android 12 a notification trampoline (a receiver starting an activity) is not allowed.
     */
    fun viewIntent(context: Context, uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).setPackage(context.packageName)

    private fun uri(host: String, id: String? = null): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(host)
        .apply { if (id != null) appendPath(id) }
        .build()
}
