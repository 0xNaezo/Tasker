package app.tasker.core.backup

import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.ContextSnapshot
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.SnapshotInputKind
import app.tasker.core.model.Source
import app.tasker.core.model.SourceKind
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Test

class MarkdownExporterTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val exportedAt = Instant.parse("2026-10-06T07:00:00Z")

    private fun at(text: String): Instant = Instant.parse(text)

    private fun task(
        id: String,
        title: String,
        status: TaskStatus = TaskStatus.OPEN,
        bucket: Bucket? = null,
        projectId: String? = null,
        position: Long = 0,
        createdAt: Instant = at("2026-10-01T06:00:00Z"),
    ) = Task(
        id = id,
        title = title,
        status = status,
        bucket = bucket,
        projectId = projectId,
        position = position,
        lastTouchedAt = createdAt,
        createdAt = createdAt,
    )

    private fun project(id: String, name: String, status: ProjectStatus = ProjectStatus.ACTIVE, position: Long = 0) =
        Project(
            id = id,
            name = name,
            status = status,
            position = position,
            lastActivityAt = at("2026-09-01T06:00:00Z"),
            createdAt = at("2026-09-01T06:00:00Z"),
        )

    private val document = TasksDocument(
        exportedAt = exportedAt,
        zone = kyiv,
        tasks = listOf(
            task("t1", "Write the article", TaskStatus.IN_PROGRESS, projectId = "blog").copy(
                planDate = date("2026-10-06"),
                estimate = Estimate.L,
                tags = listOf("writing"),
                note = "Draft in Docs\r\nKeep it short\n",
                lastTouchedAt = at("2026-10-06T06:00:00Z"),
            ),
            task("t2", "Fix the bike", TaskStatus.PAUSED).copy(lastTouchedAt = at("2026-10-05T15:00:00Z")),
            task(
                "t3",
                "Call the bank",
                bucket = Bucket.TODAY,
                position = 2,
            ).copy(deadline = Deadline(date("2026-10-06")), estimate = Estimate.S),
            task("t4", "Submit the report", bucket = Bucket.TODAY, position = 1).copy(
                deadline = Deadline(date("2026-10-09"), LocalTime.of(18, 0), kyiv),
                tags = listOf("client", "work"),
            ),
            task("t5", "Plan the trip", bucket = Bucket.WEEK).copy(
                planDate = date("2026-10-08"),
                deadline = Deadline(date("2026-10-20"), LocalTime.of(9, 30), ZoneId.of("Europe/Warsaw")),
            ),
            task("t6", "Learn Italian", bucket = Bucket.SOMEDAY).copy(inReview = true),
            task("t7", "Read later", createdAt = at("2026-10-06T05:00:00Z")),
            task("t8", "Buy milk", createdAt = at("2026-10-05T05:00:00Z")),
            task("t9", "Draft outline", bucket = Bucket.WEEK, projectId = "blog", position = 1).copy(estimate = Estimate.M),
            task("t10", "Pick a theme", projectId = "blog", position = 2),
            task("t11", "Morning exercise", TaskStatus.DONE).copy(completedAt = at("2026-10-06T04:30:00Z"), offPlan = true),
            task("t12", "Send invoice", TaskStatus.DONE, projectId = "blog").copy(completedAt = at("2026-10-04T10:00:00Z")),
            task("t13", "Read the link", TaskStatus.ARCHIVED).copy(
                archivedAt = at("2026-10-02T09:00:00Z"),
                archiveReason = ArchiveReason.TTL_SKIPS,
                note = "multi\n\nparagraph",
            ),
            task("t14", "Paint the walls", TaskStatus.ARCHIVED, projectId = "reno").copy(
                archivedAt = at("2026-09-30T12:00:00Z"),
                archiveReason = ArchiveReason.PROJECT_ARCHIVED,
            ),
        ),
        projects = listOf(
            project("old", "Old idea", ProjectStatus.ARCHIVED).copy(archivedAt = at("2026-09-01T12:00:00Z")),
            project("reno", "Renovation", ProjectStatus.COMPLETED).copy(completedAt = at("2026-09-30T12:00:00Z")),
            project("garden", "Garden", position = 2).copy(inReview = true),
            project("blog", "Blog", position = 1).copy(outcome = "Four articles published"),
        ),
        snapshots = listOf(
            ContextSnapshot("s1", "t2", "Wheel is off", SnapshotInputKind.TEXT, createdAt = at("2026-10-04T15:00:00Z")),
            ContextSnapshot("s2", "t2", "Stopped at the brakes", SnapshotInputKind.VOICE, "Buy brake pads", at("2026-10-05T15:00:00Z")),
        ),
        sources = listOf(
            Source("l1", "t7", SourceKind.LINK, url = "https://example.com/a"),
            Source("l2", "t8", SourceKind.APP, appPackage = "org.telegram.messenger"),
        ),
    )

    @Test
    fun `tasks md groups tasks by status, bucket and project with the archive last`() {
        val expected = """
            # Tasker export

            Exported 2026-10-06 10:00 (Europe/Kyiv) · 14 tasks · 4 projects

            ## In progress

            - [ ] Write the article · plan 2026-10-06 · estimate L · @writing · project Blog
              Draft in Docs
              Keep it short

            ## Paused

            - [ ] Fix the bike
              Context 2026-10-05: Stopped at the brakes · next step: Buy brake pads

            ## Today

            - [ ] Submit the report · deadline 2026-10-09 18:00 · @client @work
            - [ ] Call the bank · deadline 2026-10-06 · estimate S

            ## Week

            - [ ] Plan the trip · deadline 2026-10-20 09:30 Europe/Warsaw · plan 2026-10-08

            ## Someday

            - [ ] Learn Italian · in review

            ## Inbox

            - [ ] Read later
              Link: https://example.com/a
            - [ ] Buy milk

            ## Projects

            ### Blog

            Outcome: Four articles published

            - [ ] Draft outline · estimate M · week
            - [ ] Pick a theme

            ### Garden · in review

            _No open tasks_

            ### Renovation · completed 2026-09-30

            _No open tasks_

            ### Old idea · archived 2026-09-01

            _No open tasks_

            ## Done

            - [x] Morning exercise · done 2026-10-06
            - [x] Send invoice · project Blog · done 2026-10-04

            ## Archive

            - Read the link · archived 2026-10-02 (skipped in review)
              multi

              paragraph
            - Paint the walls · project Renovation · archived 2026-09-30 (with its project)
        """.trimIndent() + "\n"

        assertThat(MarkdownExporter.render(document)).isEqualTo(expected)
    }

    @Test
    fun `an empty export keeps every heading`() {
        val expected = """
            # Tasker export

            Exported 2026-10-06 10:00 (Europe/Kyiv) · 0 tasks · 0 projects

            ## In progress

            _No tasks_

            ## Paused

            _No tasks_

            ## Today

            _No tasks_

            ## Week

            _No tasks_

            ## Someday

            _No tasks_

            ## Inbox

            _No tasks_

            ## Projects

            _No projects_

            ## Done

            _No tasks_

            ## Archive

            _No tasks_
        """.trimIndent() + "\n"

        assertThat(MarkdownExporter.render(TasksDocument(exportedAt, kyiv, emptyList()))).isEqualTo(expected)
    }

    @Test
    fun `titles stay on one line`() {
        val doc =
            TasksDocument(
                exportedAt,
                kyiv,
                listOf(task("a", "  first\r\n  second  "), task("b", " \n ")),
                listOf(project("p", "One\nproject")),
            )

        val lines = MarkdownExporter.render(doc).lines()

        assertThat(lines).contains("- [ ] first second")
        assertThat(lines).contains("- [ ] (untitled)")
        assertThat(lines).contains("### One project")
    }

    @Test
    fun `every task appears exactly once whatever its state`() {
        val random = Random(7)
        val projects = listOf(
            project("p1", "Active"),
            project("p2", "Completed", ProjectStatus.COMPLETED),
            project("p3", "Archived", ProjectStatus.ARCHIVED),
        )
        val tasks = (1..300).map { n ->
            task(
                id = "t$n",
                title = "Task number $n.",
                status = TaskStatus.entries.random(random),
                bucket = (Bucket.entries + null).random(random),
                projectId = listOf(null, "p1", "p2", "p3", "missing").random(random),
                position = random.nextLong(0, 10),
                createdAt = at("2026-09-01T00:00:00Z").plusSeconds(random.nextLong(0, 1_000_000)),
            ).let { if (it.status == TaskStatus.DONE) it.copy(completedAt = it.createdAt) else it }
        }

        val items = MarkdownExporter.render(TasksDocument(exportedAt, kyiv, tasks, projects)).lines().filter { it.startsWith("- ") }

        assertThat(items).hasSize(tasks.size)
        tasks.forEach { task -> assertThat(items.count { it.contains("${task.title} ") || it.endsWith(task.title) }).isEqualTo(1) }
    }
}
