package app.tasker.feature.tasks

import androidx.navigation3.runtime.NavKey
import app.tasker.core.model.ProjectId
import kotlinx.serialization.Serializable

/** The "Tasks" tab: Week, Someday and Projects (§14.2). */
@Serializable
data object TasksKey : NavKey

/** A project card (EXE-4, EXE-5). */
@Serializable
data class ProjectKey(val projectId: ProjectId) : NavKey
