package app.tasker.feature.capture

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import app.tasker.core.model.CaptureChannel

/** Entry points into quick capture for the widget, the tile and launcher shortcuts (CAP-6). */
object CaptureIntents {
    private const val EXTRA_CHANNEL = "app.tasker.extra.CAPTURE_CHANNEL"
    internal const val EXTRA_VOICE = "app.tasker.extra.CAPTURE_VOICE"

    fun quickCapture(context: Context, channel: CaptureChannel, voice: Boolean = false): Intent =
        Intent(context, QuickCaptureActivity::class.java)
            .putExtra(EXTRA_CHANNEL, channel.name)
            .putExtra(EXTRA_VOICE, voice)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    internal fun channelOf(intent: Intent): CaptureChannel =
        intent.getStringExtra(EXTRA_CHANNEL)?.let { name -> CaptureChannel.entries.firstOrNull { it.name == name } }
            ?: CaptureChannel.WIDGET

    /** Android 13+: the system dialog that adds the capture tile to quick settings (§13). */
    val canRequestTile: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun requestAddTile(context: Context, onResult: (added: Boolean) -> Unit) {
        val manager = context.getSystemService(StatusBarManager::class.java) ?: return onResult(false)
        manager.requestAddTileService(
            ComponentName(context, CaptureTileService::class.java),
            context.getString(R.string.capture_tile_label),
            Icon.createWithResource(context, R.drawable.ic_capture_tile),
            context.mainExecutor,
        ) { result ->
            onResult(
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
            )
        }
    }
}
