package app.tasker.feature.search

import app.tasker.core.data.search.HorizonFilter
import app.tasker.core.data.search.SearchFilters
import app.tasker.core.model.Bucket
import app.tasker.core.model.SourceKind
import app.tasker.core.model.TaskStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FilterStateTest {
    @Test
    fun `no chip selected means no filter, so the archive stays in the results`() {
        val filters = FilterState()

        assertThat(filters.isEmpty).isTrue()
        assertThat(filters.toSearchFilters()).isEqualTo(SearchFilters())
        assertThat(filters.toSearchFilters().isEmpty).isTrue()
    }

    @Test
    fun `every chip maps onto its search filter`() {
        val filters = FilterState(
            status = StatusChoice.ARCHIVED,
            horizon = HorizonChoice.WEEK,
            projectId = "project-1",
            tag = "home",
            source = SourceKind.GITHUB_PR,
            deadline = DeadlineChoice.WITH,
        )

        assertThat(filters.isEmpty).isFalse()
        assertThat(filters.toSearchFilters()).isEqualTo(
            SearchFilters(
                statuses = setOf(TaskStatus.ARCHIVED),
                horizon = HorizonFilter.In(Bucket.WEEK),
                projectId = "project-1",
                tag = "home",
                sourceKind = SourceKind.GITHUB_PR,
                hasDeadline = true,
            ),
        )
    }

    @Test
    fun `active status covers open, in progress and paused but not done or archived`() {
        val statuses = FilterState(status = StatusChoice.ACTIVE).toSearchFilters().statuses

        assertThat(statuses).containsExactly(TaskStatus.OPEN, TaskStatus.IN_PROGRESS, TaskStatus.PAUSED)
    }

    @Test
    fun `inbox and no deadline map to their own conditions`() {
        val filters = FilterState(horizon = HorizonChoice.INBOX, deadline = DeadlineChoice.WITHOUT).toSearchFilters()

        assertThat(filters.horizon).isEqualTo(HorizonFilter.Inbox)
        assertThat(filters.hasDeadline).isFalse()
    }

    @Test
    fun `every single status choice keeps exactly its own status`() {
        StatusChoice.entries.filter { it != StatusChoice.ACTIVE }.forEach { choice ->
            assertThat(FilterState(status = choice).toSearchFilters().statuses).containsExactly(TaskStatus.valueOf(choice.name))
        }
    }

    @Test
    fun `filters survive the saved state round trip`() {
        val filters = FilterState(
            status = StatusChoice.DONE,
            horizon = HorizonChoice.SOMEDAY,
            projectId = "p",
            tag = "work: client",
            source = SourceKind.LINK,
            deadline = DeadlineChoice.WITHOUT,
        )
        val saved = filters.toSaved()

        assertThat(FilterState.fromSaved { saved[it] }).isEqualTo(filters)
        assertThat(FilterState.fromSaved { FilterState().toSaved()[it] }).isEqualTo(FilterState())
    }

    @Test
    fun `unknown saved values are dropped instead of failing`() {
        val restored = FilterState.fromSaved { key -> if (key.endsWith("status") || key.endsWith("source")) "REMOVED" else null }

        assertThat(restored).isEqualTo(FilterState())
    }
}
