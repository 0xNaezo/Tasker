package app.tasker.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.data.repository.ProjectSummary
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.Bucket
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.DragHandle
import app.tasker.core.ui.component.DraggableItem
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.PauseDialog
import app.tasker.core.ui.component.ProjectPickerDialog
import app.tasker.core.ui.component.ReasonLabel
import app.tasker.core.ui.component.RowAction
import app.tasker.core.ui.component.SectionHeader
import app.tasker.core.ui.component.TaskRow
import app.tasker.core.ui.component.WipLimitDialog
import app.tasker.core.ui.component.rememberDragDropState

private enum class TasksTab { WEEK, SOMEDAY, PROJECTS }

/** "Tasks" tab (§14.2): Week, Someday and Projects with manual order (DAT-5) and filters. */
@Composable
fun TasksScreen(
    onOpenTask: (TaskId) -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wip by viewModel.wipOutcome.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())
    Column(modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        PrimaryTabRow(selectedTabIndex = tab) {
            TasksTab.entries.forEachIndexed { index, item ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            stringResource(
                                when (item) {
                                    TasksTab.WEEK -> UiR.string.bucket_week
                                    TasksTab.SOMEDAY -> UiR.string.bucket_someday
                                    TasksTab.PROJECTS -> R.string.tasks_projects
                                },
                            ),
                        )
                    },
                )
            }
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        when (TasksTab.entries[tab]) {
            TasksTab.WEEK -> BucketList(Bucket.WEEK, state.week, state, viewModel, onOpenTask, listPadding)
            TasksTab.SOMEDAY -> BucketList(Bucket.SOMEDAY, state.someday, state, viewModel, onOpenTask, listPadding)
            TasksTab.PROJECTS -> ProjectsList(state.projects, viewModel, onOpenProject, listPadding)
        }
    }
    wip?.let { WipLimitDialog(it.task, it.inProgress, it.limit, onPause = viewModel::pauseOther, onDismiss = viewModel::dismissWip) }
}

@Composable
@Suppress("LongMethod")
private fun BucketList(
    bucket: Bucket,
    tasks: List<Task>,
    state: TasksUiState,
    viewModel: TasksViewModel,
    onOpenTask: (TaskId) -> Unit,
    contentPadding: PaddingValues,
) {
    val filter = state.filter
    val visible = tasks.filter(filter::matches)
    var order by remember(visible.map { it.id }) { mutableStateOf(visible.map { it.id }) }
    val byId = visible.associateBy { it.id }
    var moved by remember { mutableStateOf<TaskId?>(null) }
    var pausing by remember { mutableStateOf<Task?>(null) }
    var choosingProject by remember { mutableStateOf<Task?>(null) }
    val listState = rememberLazyListState()
    val drag = rememberDragDropState(
        listState = listState,
        onMove = { from, to ->
            val fromId = from as? String
            val toId = to as? String
            if (fromId !in byId || toId !in byId) return@rememberDragDropState false
            order = order.toMutableList().apply { add(indexOf(toId), removeAt(indexOf(fromId))) }
            moved = fromId
            true
        },
        onDrop = {
            moved?.let { viewModel.reorder(it, order) }
            moved = null
        },
    )
    fun move(task: Task, delta: Int) {
        val index = order.indexOf(task.id)
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (index < 0 || index == target) return
        order = order.toMutableList().apply { add(target, removeAt(index)) }
        viewModel.reorder(task.id, order)
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "filters") { Filters(state, viewModel::setFilter) }
        if (order.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(if (filter.isActive) R.string.tasks_empty_filtered else R.string.tasks_empty),
                    icon = if (bucket == Bucket.WEEK) Icons.Outlined.DateRange else Icons.Outlined.NightsStay,
                )
            }
        }
        items(order.mapNotNull { byId[it] }, key = { it.id }) { task ->
            DraggableItem(drag, key = task.id) {
                TaskRow(
                    task = task,
                    onClick = { onOpenTask(task.id) },
                    onComplete = { viewModel.toggleDone(task) },
                    onPostpone = { viewModel.postpone(task, it) },
                    projectName = task.projectId?.let { state.projectNames[it] },
                    actions = rowActions(
                        task = task,
                        bucket = bucket,
                        viewModel = viewModel,
                        onPause = { pausing = task },
                        onProject = { choosingProject = task },
                        onMove = { delta -> move(task, delta) },
                    ),
                    trailing = { DragHandle(drag, key = task.id) },
                )
            }
        }
    }
    pausing?.let { task ->
        PauseDialog(task, onDismiss = { pausing = null }, onPause = { note ->
            pausing = null
            viewModel.pause(task, note)
        })
    }
    choosingProject?.let { task ->
        ProjectPickerDialog(
            projects = state.activeProjects,
            current = task.projectId,
            onDismiss = { choosingProject = null },
            onPick = { id ->
                choosingProject = null
                viewModel.setProject(task, id)
            },
            onCreate = { name ->
                choosingProject = null
                viewModel.moveToNewProject(task, name)
            },
        )
    }
}

@Composable
private fun rowActions(
    task: Task,
    bucket: Bucket,
    viewModel: TasksViewModel,
    onPause: () -> Unit,
    onProject: () -> Unit,
    onMove: (Int) -> Unit,
): List<RowAction> = buildList {
    when (task.status) {
        TaskStatus.OPEN -> add(RowAction(stringResource(UiR.string.action_start), Icons.Outlined.PlayArrow) { viewModel.start(task) })
        TaskStatus.PAUSED -> add(RowAction(stringResource(UiR.string.action_resume), Icons.Outlined.PlayArrow) { viewModel.start(task) })
        TaskStatus.IN_PROGRESS -> add(RowAction(stringResource(UiR.string.action_pause), Icons.Outlined.Pause, onPause))
        else -> Unit
    }
    add(RowAction(stringResource(UiR.string.action_add_to_plan), Icons.Outlined.PlaylistAdd) { viewModel.addToPlan(task) })
    add(RowAction(stringResource(UiR.string.action_to_today), Icons.Outlined.WbSunny) { viewModel.move(task, Bucket.TODAY) })
    if (bucket !=
        Bucket.WEEK
    ) {
        add(RowAction(stringResource(UiR.string.action_to_week), Icons.Outlined.DateRange) { viewModel.move(task, Bucket.WEEK) })
    }
    if (bucket != Bucket.SOMEDAY) {
        add(RowAction(stringResource(UiR.string.action_to_someday), Icons.Outlined.NightsStay) { viewModel.move(task, Bucket.SOMEDAY) })
    }
    add(RowAction(stringResource(UiR.string.action_to_project), Icons.Outlined.Folder, onProject))
    add(RowAction(stringResource(UiR.string.action_move_up), Icons.Outlined.ArrowUpward) { onMove(-1) })
    add(RowAction(stringResource(UiR.string.action_move_down), Icons.Outlined.ArrowDownward) { onMove(1) })
    add(RowAction(stringResource(UiR.string.action_archive), Icons.Outlined.Archive) { viewModel.archive(task) })
}

@Composable
private fun Filters(state: TasksUiState, onFilter: (TaskFilter) -> Unit) {
    val filter = state.filter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s),
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
    ) {
        FilterChip(
            selected = filter.withDeadline,
            onClick = { onFilter(filter.copy(withDeadline = !filter.withDeadline)) },
            label = { Text(stringResource(R.string.tasks_filter_deadline)) },
            leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
        )
        state.activeProjects.forEach { project ->
            FilterChip(
                selected = filter.projectId == project.id,
                onClick = { onFilter(filter.copy(projectId = if (filter.projectId == project.id) null else project.id)) },
                label = { Text(project.name, maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
            )
        }
        state.tags.forEach { tag ->
            FilterChip(
                selected = filter.tag.equals(tag, ignoreCase = true),
                onClick = { onFilter(filter.copy(tag = if (filter.tag.equals(tag, ignoreCase = true)) null else tag)) },
                label = { Text("@$tag") },
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun ProjectsList(
    projects: List<ProjectSummary>,
    viewModel: TasksViewModel,
    onOpenProject: (ProjectId) -> Unit,
    contentPadding: PaddingValues,
) {
    val active = projects.filter { it.project.status == ProjectStatus.ACTIVE }
    val completed = projects.filter { it.project.status == ProjectStatus.COMPLETED }
    val archived = projects.filter { it.project.status == ProjectStatus.ARCHIVED }
    var order by remember(active.map { it.project.id }) { mutableStateOf(active.map { it.project.id }) }
    val byId = active.associateBy { it.project.id }
    var moved by remember { mutableStateOf<ProjectId?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val drag = rememberDragDropState(
        listState = listState,
        onMove = { from, to ->
            val fromId = from as? String
            val toId = to as? String
            if (fromId !in byId || toId !in byId) return@rememberDragDropState false
            order = order.toMutableList().apply { add(indexOf(toId), removeAt(indexOf(fromId))) }
            moved = fromId
            true
        },
        onDrop = {
            moved?.let { viewModel.reorderProjects(it, order) }
            moved = null
        },
    )
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "new") {
            OutlinedButton(
                onClick = { creating = true },
                modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.padding(end = TaskerTheme.spacing.s))
                Text(stringResource(UiR.string.project_new))
            }
        }
        if (active.isEmpty()) {
            item(key = "empty") { EmptyState(title = stringResource(R.string.tasks_projects_empty), icon = Icons.Outlined.Folder) }
        }
        items(order.mapNotNull { byId[it] }, key = { it.project.id }) { summary ->
            DraggableItem(drag, key = summary.project.id) {
                ProjectRow(summary, onClick = { onOpenProject(summary.project.id) }) { DragHandle(drag, key = summary.project.id) }
            }
        }
        collapsible("completed", R.string.tasks_projects_completed, completed, showCompleted, {
            showCompleted = !showCompleted
        }, onOpenProject)
        collapsible("archived", R.string.tasks_projects_archived, archived, showArchived, { showArchived = !showArchived }, onOpenProject)
    }
    if (creating) {
        NewProjectDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                creating = false
                viewModel.createProject(name, onOpenProject)
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.collapsible(
    key: String,
    title: Int,
    projects: List<ProjectSummary>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenProject: (ProjectId) -> Unit,
) {
    if (projects.isEmpty()) return
    item(key = "$key-header") {
        SectionHeader(
            title = stringResource(title, projects.size),
            modifier = Modifier.clickable(onClick = onToggle),
            trailing = {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(if (expanded) R.string.tasks_collapse else R.string.tasks_expand),
                    )
                }
            },
        )
    }
    if (expanded) {
        items(projects, key = { "$key-${it.project.id}" }) { summary ->
            ProjectRow(summary, onClick = { onOpenProject(summary.project.id) })
        }
    }
}

@Composable
private fun ProjectRow(summary: ProjectSummary, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val project = summary.project
    Surface {
        ListItem(
            headlineContent = { Text(project.name, maxLines = 2) },
            supportingContent = {
                Column {
                    project.outcome?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
                    if (summary.noNextStep) {
                        ReasonLabel(stringResource(UiR.string.review_no_next_step), color = TaskerTheme.colors.review)
                    } else if (project.status == ProjectStatus.ACTIVE) {
                        Text(pluralStringResource(R.plurals.tasks_project_count, summary.activeTasks, summary.activeTasks))
                    }
                }
            },
            leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
            trailingContent = trailing,
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Composable
internal fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiR.string.project_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(UiR.string.project_name_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(UiR.string.project_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}
