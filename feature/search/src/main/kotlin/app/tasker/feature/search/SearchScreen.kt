package app.tasker.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import app.tasker.core.data.search.SearchHit
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.Bucket
import app.tasker.core.model.SourceKind
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.ReasonLabel
import app.tasker.core.ui.component.TaskChips
import app.tasker.core.ui.format.Formats
import kotlinx.coroutines.flow.filter

/**
 * "Search" (§14.2, SRC-1, SRC-2): the field gets focus on opening and results follow the typed text after a short pause;
 * filter chips narrow them by status, horizon, project, tag, source type and deadline. Matches are highlighted, archived
 * tasks are part of the results, marked and restored in one tap (TTL-8). The top bar leads to the archive.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onOpenArchive: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hits = viewModel.results.collectAsLazyPagingItems()
    SearchContent(
        query = viewModel.query,
        state = state,
        hits = hits,
        onQueryChange = viewModel::onQueryChange,
        onFiltersChange = viewModel::setFilters,
        onRestore = viewModel::restore,
        onBack = onBack,
        onOpenTask = onOpenTask,
        onOpenArchive = onOpenArchive,
        modifier = modifier,
    )
}

/** Stateless body of [SearchScreen]. */
@Composable
internal fun SearchContent(
    query: String,
    state: SearchUiState,
    hits: LazyPagingItems<SearchHit>,
    onQueryChange: (String) -> Unit,
    onFiltersChange: (FilterState) -> Unit,
    onRestore: (Task) -> Unit,
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onOpenArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { SearchTopBar(query, onQueryChange, onBack, onOpenArchive) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            FilterBar(state, onFiltersChange)
            val current = SearchRequest(query.trim(), state.filters)
            val refresh = hits.loadState.refresh
            val loading = state.searched != current || refresh is LoadState.Loading
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PROGRESS_HEIGHT),
            ) {
                if (loading && !current.isIdle) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                current.isIdle -> IdleHint(state.archiveCount, onOpenArchive)
                refresh is LoadState.Error && hits.itemCount == 0 -> EmptyState(
                    title = stringResource(R.string.search_error),
                    icon = Icons.Outlined.SearchOff,
                    actionLabel = stringResource(R.string.search_retry),
                    onAction = hits::retry,
                )
                hits.itemCount == 0 && !loading -> EmptyState(
                    title = stringResource(R.string.search_nothing_found),
                    body = stringResource(R.string.search_nothing_found_body),
                    icon = Icons.Outlined.SearchOff,
                )
                else -> ResultList(state, hits, onOpenTask, onRestore)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onBack: () -> Unit, onOpenArchive: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Focus only on the first opening, not when coming back from a task card.
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusedOnce) {
            focusedOnce = true
            focus.requestFocus()
        }
    }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = if (query.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = {
                            onQueryChange("")
                            focus.requestFocus()
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.search_back))
            }
        },
        actions = {
            IconButton(onClick = onOpenArchive) {
                Icon(Icons.Outlined.Inventory2, contentDescription = stringResource(R.string.search_open_archive))
            }
        },
    )
}

/** SRC-2 filters in the order of the spec: status, horizon, project, tag, source type, deadline. */
@Composable
private fun FilterBar(state: SearchUiState, onFiltersChange: (FilterState) -> Unit) {
    val filters = state.filters
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = TaskerTheme.spacing.l),
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuChip(
            category = stringResource(R.string.search_filter_status),
            selected = filters.status,
            options = StatusChoice.entries.map { it to statusLabel(it) },
            onSelect = { onFiltersChange(filters.copy(status = it)) },
        )
        MenuChip(
            category = stringResource(R.string.search_filter_horizon),
            selected = filters.horizon,
            options = HorizonChoice.entries.map { it to horizonLabel(it) },
            onSelect = { onFiltersChange(filters.copy(horizon = it)) },
        )
        if (state.projects.isNotEmpty() || filters.projectId != null) {
            MenuChip(
                category = stringResource(R.string.search_filter_project),
                selected = filters.projectId,
                options = state.projects.map { it.id to it.name },
                onSelect = { onFiltersChange(filters.copy(projectId = it)) },
            )
        }
        if (state.tags.isNotEmpty() || filters.tag != null) {
            MenuChip(
                category = stringResource(R.string.search_filter_tag),
                selected = filters.tag,
                options = state.tags.map { it.name to "@${it.name}" },
                onSelect = { onFiltersChange(filters.copy(tag = it)) },
            )
        }
        MenuChip(
            category = stringResource(R.string.search_filter_source),
            selected = filters.source,
            options = SourceKind.entries.map { it to sourceLabel(it) },
            onSelect = { onFiltersChange(filters.copy(source = it)) },
        )
        MenuChip(
            category = stringResource(R.string.search_filter_deadline),
            selected = filters.deadline,
            options = DeadlineChoice.entries.map { it to deadlineLabel(it) },
            onSelect = { onFiltersChange(filters.copy(deadline = it)) },
        )
        if (!filters.isEmpty) {
            AssistChip(
                onClick = { onFiltersChange(FilterState()) },
                label = { Text(stringResource(R.string.search_filters_clear)) },
                leadingIcon = {
                    Icon(Icons.Outlined.FilterAltOff, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                },
            )
        }
    }
}

/** A filter chip with a single-choice menu; "Any" clears it. The chip names the category and the chosen value. */
@Composable
private fun <T> MenuChip(category: String, selected: T?, options: List<Pair<T, String>>, onSelect: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val value = selected?.let { choice -> options.firstOrNull { it.first == choice }?.second ?: choice.toString() }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = {
                Text(
                    text = if (value == null) category else stringResource(R.string.search_filter_value, category, value),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = if (selected == null) {
                null
            } else {
                { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            },
            trailingIcon = {
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuOption(stringResource(R.string.search_filter_any), checked = selected == null) {
                open = false
                onSelect(null)
            }
            options.forEach { (option, label) ->
                MenuOption(label, checked = option == selected) {
                    open = false
                    onSelect(option)
                }
            }
        }
    }
}

@Composable
private fun MenuOption(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { if (checked) Icon(Icons.Outlined.Check, contentDescription = null) },
        modifier = Modifier.semantics { selected = checked },
    )
}

@Composable
private fun IdleHint(archiveCount: Int, onOpenArchive: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.search_idle_title),
        body = stringResource(R.string.search_idle_body),
        icon = Icons.Outlined.Search,
        actionLabel = if (archiveCount > 0) {
            pluralStringResource(R.plurals.search_archive_count, archiveCount, archiveCount)
        } else {
            stringResource(R.string.search_archive_open)
        },
        onAction = onOpenArchive,
    )
}

@Composable
private fun ResultList(
    state: SearchUiState,
    hits: LazyPagingItems<SearchHit>,
    onOpenTask: (TaskId) -> Unit,
    onRestore: (Task) -> Unit,
) {
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    // Scrolling the results hides the keyboard: there is more room to look through them.
    LaunchedEffect(listState, keyboard) {
        snapshotFlow { listState.isScrollInProgress }.filter { it }.collect { keyboard?.hide() }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(
            count = hits.itemCount,
            key = hits.itemKey { it.task.id },
            contentType = hits.itemContentType { "hit" },
        ) { index ->
            hits[index]?.let { hit ->
                SearchResultRow(
                    hit = hit,
                    projectName = hit.task.projectId?.let { state.projectNames[it] },
                    query = state.searched.query,
                    onOpen = { onOpenTask(hit.task.id) },
                    onRestore = { onRestore(hit.task) },
                )
            }
        }
        if (hits.loadState.append is LoadState.Loading) {
            item(key = "append", contentType = "append") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(TaskerTheme.spacing.l),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(APPEND_PROGRESS_SIZE)) }
            }
        }
    }
}

/**
 * A result: the title and, when the note matched, an excerpt of it, with the matches in bold on a tinted background;
 * status, place and the usual chips; "Restore" for archived tasks (one tap, TTL-8).
 */
@Composable
internal fun SearchResultRow(hit: SearchHit, projectName: String?, query: String, onOpen: () -> Unit, onRestore: () -> Unit) {
    val task = hit.task
    val highlight = SpanStyle(
        fontWeight = FontWeight.Bold,
        background = MaterialTheme.colorScheme.tertiaryContainer,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    val muted = !task.status.isActive
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(onClickLabel = stringResource(UiR.string.action_open), onClick = onOpen)
            .padding(
                start = TaskerTheme.spacing.l,
                end = TaskerTheme.spacing.s,
                top = TaskerTheme.spacing.s,
                bottom = TaskerTheme.spacing.s,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs)) {
            Text(
                text = highlighted(task.title, hit.titleHighlights, highlight),
                style = MaterialTheme.typography.bodyLarge,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val note = task.note
            if (note != null && hit.noteHighlights.isNotEmpty()) {
                val snippet = remember(note, hit.noteHighlights) { Snippets.around(note, hit.noteHighlights) }
                Text(
                    text = highlighted(snippet.text, snippet.highlights, highlight),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlaceChips(task)
            TaskChips(task = task, projectName = projectName)
            if (query.isNotBlank() && hit.titleHighlights.isEmpty() && hit.noteHighlights.isEmpty()) {
                ReasonLabel(stringResource(R.string.search_matched_elsewhere))
            }
        }
        if (task.status == TaskStatus.ARCHIVED) {
            TextButton(onClick = onRestore) { Text(stringResource(UiR.string.action_restore)) }
        }
    }
}

@Composable
private fun statusLabel(choice: StatusChoice): String = when (choice) {
    StatusChoice.ACTIVE -> stringResource(R.string.search_status_active)
    StatusChoice.OPEN -> Formats.status(TaskStatus.OPEN)
    StatusChoice.IN_PROGRESS -> Formats.status(TaskStatus.IN_PROGRESS)
    StatusChoice.PAUSED -> Formats.status(TaskStatus.PAUSED)
    StatusChoice.DONE -> Formats.status(TaskStatus.DONE)
    StatusChoice.ARCHIVED -> Formats.status(TaskStatus.ARCHIVED)
}

@Composable
private fun horizonLabel(choice: HorizonChoice): String = when (choice) {
    HorizonChoice.INBOX -> Formats.bucket(null)
    HorizonChoice.TODAY -> Formats.bucket(Bucket.TODAY)
    HorizonChoice.WEEK -> Formats.bucket(Bucket.WEEK)
    HorizonChoice.SOMEDAY -> Formats.bucket(Bucket.SOMEDAY)
}

@Composable
private fun deadlineLabel(choice: DeadlineChoice): String = stringResource(
    when (choice) {
        DeadlineChoice.WITH -> R.string.search_deadline_with
        DeadlineChoice.WITHOUT -> R.string.search_deadline_without
    },
)

@Composable
private fun sourceLabel(kind: SourceKind): String = stringResource(
    when (kind) {
        SourceKind.TELEGRAM -> R.string.search_source_telegram
        SourceKind.GITHUB_ISSUE -> R.string.search_source_github_issue
        SourceKind.GITHUB_PR -> R.string.search_source_github_pr
        SourceKind.LINK -> R.string.search_source_link
        SourceKind.APP -> R.string.search_source_app
    },
)

private val PROGRESS_HEIGHT = 4.dp
private val ROW_MIN_HEIGHT = 56.dp
private val APPEND_PROGRESS_SIZE = 24.dp
