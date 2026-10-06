package app.tasker.feature.task

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TagsTest {
    @Test
    fun `tags are split by commas and spaces with optional at signs`() {
        assertThat(parseTags("@work, call  @Home\nwork")).containsExactly("work", "call", "Home").inOrder()
    }

    @Test
    fun `blank input clears tags`() {
        assertThat(parseTags("  , @ ")).isEmpty()
    }
}
