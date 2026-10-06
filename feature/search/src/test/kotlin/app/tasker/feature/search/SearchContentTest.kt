package app.tasker.feature.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tasker.core.data.search.SearchHit
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import app.tasker.core.ui.DayContext
import app.tasker.core.ui.LocalDayContext
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchContentTest {
    // Paging hands pages over on AndroidUiDispatcher.Main and resumes on the effect
    // dispatcher; an unconfined one lets the presenter settle within the looper idle.
    @get:Rule
    val compose = createComposeRule(effectContext = UnconfinedTestDispatcher())

    private val today = date("2026-10-06")
    private val loaded = LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true))

    private class Calls {
        var query: String? = null
        var filters: FilterState? = null
        var restored: Task? = null
        var opened: TaskId? = null
        var archive = 0
    }

    private fun showSearch(query: String, state: SearchUiState, hits: List<SearchHit>): Calls {
        val calls = Calls()
        val pages = flowOf(PagingData.from(hits, loaded))
        compose.setContent {
            TaskerTheme {
                CompositionLocalProvider(
                    LocalDayContext provides DayContext(today, Instant.parse("2026-10-06T07:00:00Z"), ZoneId.of("UTC")),
                ) {
                    SearchContent(
                        query = query,
                        state = state,
                        hits = pages.collectAsLazyPagingItems(),
                        onQueryChange = { calls.query = it },
                        onFiltersChange = { calls.filters = it },
                        onRestore = { calls.restored = it },
                        onBack = {},
                        onOpenTask = { calls.opened = it },
                        onOpenArchive = { calls.archive++ },
                    )
                }
            }
        }
        return calls
    }

    @Test
    fun `without a query or filters the screen shows the hint and leads to the archive`() {
        val calls = showSearch("", SearchUiState(archiveCount = 3), emptyList())

        compose.onNodeWithText("Search all tasks").assertIsDisplayed()
        compose.onNodeWithText("Archive: 3 tasks").performClick()
        compose.onNodeWithContentDescription("Archive").performClick()

        assertThat(calls.archive).isEqualTo(2)
    }

    @Test
    fun `typing goes to the view model`() {
        val calls = showSearch("", SearchUiState(), emptyList())

        compose.onNode(hasSetTextAction()).performTextInput("report")

        assertThat(calls.query).isEqualTo("report")
    }

    @Test
    fun `a settled query without hits says nothing was found`() {
        showSearch("zzz", SearchUiState(searched = SearchRequest("zzz")), emptyList())

        // Paging delivers the (empty) page asynchronously.
        compose.waitUntilAtLeastOneExists(hasText("Nothing found"), PAGE_TIMEOUT_MS)
        compose.onNodeWithText("Nothing found").assertIsDisplayed()
    }

    @Test
    fun `results open the task and archived ones are marked and restored in one tap`() {
        val active = aTask(title = "Pay the invoice", id = "t1", bucket = Bucket.WEEK)
        val archived = aTask(title = "Renew the passport", id = "t2", bucket = Bucket.SOMEDAY).copy(status = TaskStatus.ARCHIVED)
        val hits = listOf(SearchHit(active, listOf(8..14), emptyList()), SearchHit(archived, emptyList(), emptyList()))

        val calls = showSearch("invoice", SearchUiState(searched = SearchRequest("invoice")), hits)
        compose.waitUntilAtLeastOneExists(hasText("Pay the invoice"), PAGE_TIMEOUT_MS)
        compose.onNodeWithContentDescription("Archived").assertIsDisplayed()
        compose.onNodeWithContentDescription("Returns to Someday").assertIsDisplayed()
        compose.onNodeWithContentDescription("Horizon: Week").assertIsDisplayed()
        compose.onNodeWithText("Matches in context notes or tags").assertIsDisplayed()
        compose.onNodeWithText("Restore").performClick()
        compose.onNodeWithText("Pay the invoice").performClick()

        assertThat(calls.restored).isEqualTo(archived)
        assertThat(calls.opened).isEqualTo("t1")
    }

    @Test
    fun `a filter chip offers its choices and names the chosen one`() {
        val calls = showSearch("", SearchUiState(), emptyList())

        compose.onNodeWithText("Status").performClick()
        compose.onNodeWithText("Done").performClick()

        assertThat(calls.filters).isEqualTo(FilterState(status = StatusChoice.DONE))
    }

    @Test
    fun `a chosen filter is spelled out and can be cleared`() {
        val calls = showSearch("", SearchUiState(filters = FilterState(horizon = HorizonChoice.WEEK)), emptyList())

        compose.onNodeWithText("Horizon: Week").assertIsDisplayed()
        compose.onNodeWithText("Clear filters").performClick()

        assertThat(calls.filters).isEqualTo(FilterState())
    }

    @Test
    fun `an archived task shows why it is there, restores and is deleted only through the menu`() {
        val task = aTask(title = "Old plan", id = "t3", bucket = Bucket.WEEK)
            .copy(status = TaskStatus.ARCHIVED, archiveReason = ArchiveReason.TTL_SKIPS)
        var restored = 0
        var deleted = 0
        compose.setContent {
            TaskerTheme {
                CompositionLocalProvider(
                    LocalDayContext provides DayContext(today, Instant.parse("2026-10-06T07:00:00Z"), ZoneId.of("UTC")),
                ) {
                    ArchivedRow(ArchivedItem(task, today), projectName = null, onOpen = {
                    }, onRestore = { restored++ }, onDelete = { deleted++ })
                }
            }
        }

        compose.onNodeWithText("Archived automatically after skips in review · today").assertIsDisplayed()
        compose.onNodeWithContentDescription("Returns to Week").assertIsDisplayed()
        compose.onNodeWithText("Restore").performClick()
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Delete forever").performClick()

        assertThat(restored).isEqualTo(1)
        assertThat(deleted).isEqualTo(1)
    }

    private companion object {
        const val PAGE_TIMEOUT_MS = 5_000L
    }
}
