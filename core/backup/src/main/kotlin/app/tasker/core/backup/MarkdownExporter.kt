package app.tasker.core.backup

import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.ContextSnapshot
import app.tasker.core.model.Deadline
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.Source
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders `tasks.md` (DATA-1, tech plan §16): all tasks, the archive included, for people to read. `export.json` stays
 * the complete copy; this file is a view of it. Headings are English and stable, so people and tools can rely on them.
 *
 * Every task appears exactly once:
 * - in progress and paused tasks under "In progress" and "Paused";
 * - other open tasks of a project under that project in "Projects";
 * - remaining open tasks under "Today", "Week", "Someday" or "Inbox" by bucket;
 * - done tasks under "Done", recently completed first;
 * - archived tasks under "Archive", the last section, recently archived first.
 *
 * A task line is a checkbox, the title and compact details: deadline, plan date, estimate, `@tags`, and the project or
 * the bucket where the section does not imply them. The note, the latest context snapshot and links follow, indented.
 */
object MarkdownExporter {
    fun render(document: TasksDocument): String = Renderer(document).render()
}

/** Top-level sections of `tasks.md` in document order. */
private enum class MarkdownSection(val heading: String) {
    IN_PROGRESS("In progress"),
    PAUSED("Paused"),
    TODAY("Today"),
    WEEK("Week"),
    SOMEDAY("Someday"),
    INBOX("Inbox"),
    PROJECTS("Projects"),
    DONE("Done"),
    ARCHIVE("Archive"),
}

private class Renderer(private val doc: TasksDocument) {
    private val out = StringBuilder()
    private val projects: Map<String, Project> = doc.projects.associateBy { it.id }
    private val latestSnapshots: Map<String, ContextSnapshot> = doc.snapshots
        .groupBy { it.taskId }
        .mapValues { (_, list) -> list.maxWith(compareBy<ContextSnapshot> { it.createdAt }.thenBy { it.id }) }
    private val links: Map<String, List<Source>> = doc.sources.filter { !it.url.isNullOrBlank() }.groupBy { it.taskId }

    fun render(): String {
        val sections = doc.tasks.groupBy(::sectionOf)
        line("# Tasker export")
        blank()
        val totals = listOf(count(doc.tasks.size, "task"), count(doc.projects.size, "project"))
        line((listOf("Exported ${dateTime(doc.exportedAt)} (${doc.zone.id})") + totals).joinToString(SEPARATOR))
        for (section in MarkdownSection.entries) {
            blank()
            line("## ${section.heading}")
            blank()
            val tasks = sections[section].orEmpty()
            if (section == MarkdownSection.PROJECTS) projects(tasks) else tasks(section, tasks.sortedWith(orderOf(section)))
        }
        return out.toString()
    }

    private fun sectionOf(task: Task): MarkdownSection = when (task.status) {
        TaskStatus.ARCHIVED -> MarkdownSection.ARCHIVE
        TaskStatus.DONE -> MarkdownSection.DONE
        TaskStatus.IN_PROGRESS -> MarkdownSection.IN_PROGRESS
        TaskStatus.PAUSED -> MarkdownSection.PAUSED
        TaskStatus.OPEN -> when {
            projectOf(task) != null -> MarkdownSection.PROJECTS
            else -> when (task.bucket) {
                Bucket.TODAY -> MarkdownSection.TODAY
                Bucket.WEEK -> MarkdownSection.WEEK
                Bucket.SOMEDAY -> MarkdownSection.SOMEDAY
                null -> MarkdownSection.INBOX
            }
        }
    }

    private fun orderOf(section: MarkdownSection): Comparator<Task> = when (section) {
        MarkdownSection.IN_PROGRESS, MarkdownSection.PAUSED -> RECENTLY_TOUCHED
        MarkdownSection.INBOX -> NEWEST_FIRST
        MarkdownSection.DONE -> RECENTLY_DONE
        MarkdownSection.ARCHIVE -> RECENTLY_ARCHIVED
        else -> MANUAL_ORDER
    }.thenBy { it.id }

    private fun tasks(section: MarkdownSection, tasks: List<Task>) {
        if (tasks.isEmpty()) {
            line("_No tasks_")
            return
        }
        tasks.forEach { task(it, section) }
    }

    private fun projects(openTasks: List<Task>) {
        if (doc.projects.isEmpty()) {
            line("_No projects_")
            return
        }
        val byProject = openTasks.groupBy { it.projectId }
        val byStatus = doc.projects.groupBy { it.status }
        val ordered = byStatus[ProjectStatus.ACTIVE].orEmpty().sortedWith(PROJECT_ORDER) +
            byStatus[ProjectStatus.COMPLETED].orEmpty().sortedWith(RECENTLY_COMPLETED) +
            byStatus[ProjectStatus.ARCHIVED].orEmpty().sortedWith(RECENTLY_ARCHIVED_PROJECT)
        ordered.forEachIndexed { index, project ->
            if (index > 0) blank()
            line("### " + (listOf(singleLine(project.name)) + projectDetails(project)).joinToString(SEPARATOR))
            blank()
            project.outcome?.takeIf { it.isNotBlank() }?.let {
                line("Outcome: ${singleLine(it)}")
                blank()
            }
            val tasks = byProject[project.id].orEmpty().sortedWith(MANUAL_ORDER.thenBy { it.id })
            if (tasks.isEmpty()) line("_No open tasks_") else tasks.forEach { task(it, MarkdownSection.PROJECTS) }
        }
    }

    private fun projectDetails(project: Project): List<String> = buildList {
        when (project.status) {
            ProjectStatus.ACTIVE -> Unit
            ProjectStatus.COMPLETED -> add("completed" + project.completedAt?.let { " ${date(it)}" }.orEmpty())
            ProjectStatus.ARCHIVED -> add("archived" + project.archivedAt?.let { " ${date(it)}" }.orEmpty())
        }
        if (project.inReview) add("in review")
    }

    private fun task(task: Task, section: MarkdownSection) {
        val box = when (task.status) {
            TaskStatus.DONE -> "- [x] "
            TaskStatus.ARCHIVED -> "- "
            else -> "- [ ] "
        }
        line(box + (listOf(singleLine(task.title).ifEmpty { "(untitled)" }) + details(task, section)).joinToString(SEPARATOR))
        task.note?.takeIf { it.isNotBlank() }?.let(::indented)
        latestSnapshots[task.id]?.let { snapshot ->
            val next = snapshot.nextStep?.takeIf { it.isNotBlank() }?.let { "${SEPARATOR}next step: ${singleLine(it)}" }.orEmpty()
            indented("Context ${date(snapshot.createdAt)}: ${snapshot.text.trim()}$next")
        }
        links[task.id].orEmpty().forEach { indented("Link: ${it.url}") }
    }

    private fun details(task: Task, section: MarkdownSection): List<String> = buildList {
        task.deadline?.let { add("deadline ${deadline(it)}") }
        task.planDate?.let { add("plan $it") }
        task.estimate?.let { add("estimate ${it.name}") }
        if (task.tags.isNotEmpty()) add(task.tags.joinToString(" ") { "@$it" })
        if (section == MarkdownSection.PROJECTS) {
            task.bucket?.let { add(it.name.lowercase(Locale.ROOT)) }
        } else {
            projectOf(task)?.let { add("project ${singleLine(it.name)}") }
        }
        if (task.inReview && task.status == TaskStatus.OPEN) add("in review")
        when (section) {
            MarkdownSection.DONE -> task.completedAt?.let { add("done ${date(it)}") }
            MarkdownSection.ARCHIVE -> {
                val reason = task.archiveReason?.let { " (${reasonText(it)})" }.orEmpty()
                add("archived" + task.archivedAt?.let { " ${date(it)}" }.orEmpty() + reason)
            }
            else -> Unit
        }
    }

    private fun projectOf(task: Task): Project? = task.projectId?.let(projects::get)

    private fun deadline(deadline: Deadline): String {
        val time = deadline.time ?: return deadline.date.toString()
        val zone = deadline.zone?.takeIf { it != doc.zone }?.let { " ${it.id}" }.orEmpty()
        return "${deadline.date} ${time.format(TIME)}$zone"
    }

    private fun date(instant: Instant): LocalDate = instant.atZone(doc.zone).toLocalDate()

    private fun dateTime(instant: Instant): String = instant.atZone(doc.zone).format(DATE_TIME)

    /** Writes [text] indented under the current list item; blank lines stay blank so the item keeps going. */
    private fun indented(text: String) {
        text.replace("\r\n", "\n").replace('\r', '\n').trim('\n').lines().forEach { part ->
            if (part.isBlank()) blank() else line(INDENT + part.trimEnd())
        }
    }

    private fun line(text: String) {
        out.append(text).append('\n')
    }

    private fun blank() {
        out.append('\n')
    }

    private companion object {
        const val SEPARATOR = " · "
        const val INDENT = "  "
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
        val LINE_BREAKS = Regex("\\s*[\\r\\n]+\\s*")

        /** Buckets and projects keep the user's manual order (DAT-5). */
        val MANUAL_ORDER: Comparator<Task> = compareBy<Task> { it.position }.thenBy { it.createdAt }
        val RECENTLY_TOUCHED: Comparator<Task> = compareByDescending<Task> { it.lastTouchedAt }.thenBy { it.createdAt }
        val NEWEST_FIRST: Comparator<Task> = compareByDescending { it.createdAt }
        val RECENTLY_DONE: Comparator<Task> = compareByDescending<Task> { it.completedAt }.thenByDescending { it.createdAt }
        val RECENTLY_ARCHIVED: Comparator<Task> = compareByDescending<Task> { it.archivedAt }.thenByDescending { it.createdAt }
        val PROJECT_ORDER: Comparator<Project> = compareBy<Project> { it.position }.thenBy { it.createdAt }.thenBy { it.id }
        val RECENTLY_COMPLETED: Comparator<Project> = compareByDescending<Project> { it.completedAt }.thenBy { it.id }
        val RECENTLY_ARCHIVED_PROJECT: Comparator<Project> = compareByDescending<Project> { it.archivedAt }.thenBy { it.id }

        fun singleLine(text: String): String = text.replace(LINE_BREAKS, " ").trim()

        fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

        fun reasonText(reason: ArchiveReason): String = when (reason) {
            ArchiveReason.USER -> "by hand"
            ArchiveReason.TTL_SKIPS -> "skipped in review"
            ArchiveReason.PROJECT_ARCHIVED -> "with its project"
            ArchiveReason.SPLIT -> "split into a project"
            ArchiveReason.MERGED -> "merged into another task"
            ArchiveReason.TELEGRAM_CANCELLED -> "cancelled in Telegram"
        }
    }
}
