package app.tasker.feature.done

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The "Done" tab: the done log by days and weeks with their summaries (LOG-1, LOG-2, EXC-7). */
@Serializable
data object DoneKey : NavKey
