package app.tasker.feature.search

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.search.SearchHit
import app.tasker.core.data.search.SearchRepository
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.Tag
import app.tasker.core.model.Task
import app.tasker.core.ui.action.TaskActions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the results show: the settled (debounced, trimmed) query and the filters. */
@Immutable
data class SearchRequest(val query: String = "", val filters: FilterState = FilterState()) {
    /** Neither a query nor a filter: nothing to search for, the screen shows a hint instead of results. */
    val isIdle: Boolean get() = query.isBlank() && filters.isEmpty
}

@Immutable
data class SearchUiState(
    val filters: FilterState = FilterState(),
    /** The request the results currently reflect; lags behind the typed text while input settles. */
    val searched: SearchRequest = SearchRequest(),
    /** All projects, archived ones included: they are filter options and name the project chips of results. */
    val projects: List<Project> = emptyList(),
    val projectNames: Map<ProjectId, String> = emptyMap(),
    val tags: List<Tag> = emptyList(),
    val archiveCount: Int = 0,
)

/**
 * "Search" (SRC-1, SRC-2, tech plan §15): full-text search over text, notes and context snapshots with filters.
 * The query is debounced, filters apply at once; the archive is part of the results and restored in one tap (TTL-8).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    search: SearchRepository,
    tasks: TaskRepository,
    private val actions: TaskActions,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    /** Text of the search field. Compose state, so typing never waits for a flow; mirrored to [savedState]. */
    var query: String by mutableStateOf(savedState.get<String>(KEY_QUERY).orEmpty())
        private set

    private val typed = MutableStateFlow(query)
    private val filters = MutableStateFlow(FilterState.fromSaved { savedState.get<String>(it) })

    private val request: StateFlow<SearchRequest> = combine(
        typed.map { it.trim() }.debounce { if (it.isEmpty()) 0L else DEBOUNCE_MS },
        filters,
        ::SearchRequest,
    ).stateIn(viewModelScope, SharingStarted.Eagerly, SearchRequest(query.trim(), filters.value))

    /** Paged hits for the current request; empty while the request is idle. */
    val results: Flow<PagingData<SearchHit>> = request
        .flatMapLatest { search.search(it.query, it.filters.toSearchFilters()) }
        .cachedIn(viewModelScope)

    val state: StateFlow<SearchUiState> = combine(
        filters,
        request,
        tasks.observeProjects(),
        tasks.observeTags(),
        tasks.observeArchiveCount(),
    ) { filters, request, projects, tags, archived ->
        val all = projects.map { it.project }
        SearchUiState(filters, request, all, all.associate { it.id to it.name }, tags, archived)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchUiState(filters.value, request.value))

    fun onQueryChange(value: String) {
        query = value
        typed.value = value
        savedState[KEY_QUERY] = value
    }

    /** Filters apply at once, without the query debounce. */
    fun setFilters(value: FilterState) {
        filters.value = value
        value.toSaved().forEach { (key, saved) -> savedState[key] = saved }
    }

    /** One-tap restore of an archived result (TTL-8): back to its bucket or project, with Undo in the snackbar. */
    fun restore(task: Task) {
        viewModelScope.launch { actions.restore(task) }
    }

    private companion object {
        const val KEY_QUERY = "query"
        const val DEBOUNCE_MS = 300L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
