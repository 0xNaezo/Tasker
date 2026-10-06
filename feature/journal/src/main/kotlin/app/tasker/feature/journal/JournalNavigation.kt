package app.tasker.feature.journal

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** "Automation journal": what rules, AI and integrations did, when and why, with undo for 30 days (AUT-1). */
@Serializable
data object JournalKey : NavKey
