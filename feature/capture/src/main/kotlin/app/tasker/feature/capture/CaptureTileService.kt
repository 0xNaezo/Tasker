package app.tasker.feature.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.tasker.core.model.CaptureChannel

/** Quick settings tile: opens quick capture and collapses the shade (CAP-6). */
class CaptureTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { openCapture() } else openCapture()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openCapture() {
        val intent = CaptureIntents.quickCapture(this, CaptureChannel.TILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
