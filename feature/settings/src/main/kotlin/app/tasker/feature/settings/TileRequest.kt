package app.tasker.feature.settings

/**
 * How settings offer the quick capture tile (CAP-6). Android 13+ adds it through a system dialog; before that the user
 * adds it by hand, and settings explain how (tech plan §5).
 */
sealed interface TileRequest {
    data object Manual : TileRequest

    /** Shows the system dialog; the callback tells whether the tile is in quick settings afterwards. */
    class System(val request: (onResult: (added: Boolean) -> Unit) -> Unit) : TileRequest
}
