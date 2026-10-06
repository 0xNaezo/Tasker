package app.tasker.feature.capture

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.tasker.core.model.CaptureChannel

/**
 * Launcher shortcuts "New task" and "By voice" (§13): a long press on the app icon opens quick capture directly.
 * Published from the foreground activity, so labels follow the app language and no rate limit applies.
 */
object CaptureShortcuts {
    private const val NEW_TASK = "new-task"
    private const val VOICE = "voice-task"
    private const val TAG = "CaptureShortcuts"

    fun publish(context: Context) {
        val shortcuts = listOf(
            shortcut(
                context,
                NEW_TASK,
                R.string.capture_shortcut_new,
                R.string.capture_shortcut_new_long,
                R.drawable.ic_shortcut_add,
                voice = false,
            ),
            shortcut(
                context,
                VOICE,
                R.string.capture_shortcut_voice,
                R.string.capture_shortcut_voice_long,
                R.drawable.ic_shortcut_voice,
                voice = true,
            ),
        )
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
            .onFailure { Log.w(TAG, "Shortcuts were not published", it) }
    }

    /** Launchers rank shortcuts by use. */
    internal fun reportUsed(context: Context, voice: Boolean) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, if (voice) VOICE else NEW_TASK) }
    }

    private fun shortcut(context: Context, id: String, short: Int, long: Int, icon: Int, voice: Boolean): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(short))
            .setLongLabel(context.getString(long))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(CaptureIntents.quickCapture(context, CaptureChannel.SHORTCUT, voice).setAction(Intent.ACTION_VIEW))
            .build()
}
