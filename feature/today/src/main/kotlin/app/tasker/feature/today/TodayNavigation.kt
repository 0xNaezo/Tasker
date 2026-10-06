package app.tasker.feature.today

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The "Today" tab. */
@Serializable
data object TodayKey : NavKey

/** "Day plan": draft, acceptance and manual edits (§10.5). */
@Serializable
data object PlanKey : NavKey
