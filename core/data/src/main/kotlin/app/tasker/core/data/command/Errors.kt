package app.tasker.core.data.command

/** Command errors are reported to the caller, never silently ignored (tech plan §8.1). */
sealed class CommandException(message: String) : IllegalStateException(message)

class TaskNotFoundException(val taskId: String) : CommandException("Task $taskId does not exist")

class ProjectNotFoundException(val projectId: String) : CommandException("Project $projectId does not exist")

class InvalidCommandException(message: String) : CommandException(message)
