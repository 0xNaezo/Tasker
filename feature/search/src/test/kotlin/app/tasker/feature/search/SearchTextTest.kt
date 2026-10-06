package app.tasker.feature.search

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchTextTest {
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)

    @Test
    fun `highlights become spans over the matched words`() {
        val text = highlighted("Сдать отчёт клиенту", listOf(6..10), bold)

        assertThat(text.text).isEqualTo("Сдать отчёт клиенту")
        assertThat(text.spanStyles.map { it.start until it.end }).containsExactly(6 until 11)
        assertThat(text.text.substring(6, 11)).isEqualTo("отчёт")
    }

    @Test
    fun `ranges outside the text are clipped or ignored`() {
        val text = highlighted("abc", listOf(1..10, 5..7, -3..0), bold)

        assertThat(text.spanStyles.map { it.start until it.end }).containsExactly(1 until 3, 0 until 1)
    }

    @Test
    fun `a short note is shown whole with line breaks as spaces`() {
        val snippet = Snippets.around("Call Anna\nabout the report", listOf(20..25))

        assertThat(snippet.text).isEqualTo("Call Anna about the report")
        assertThat(snippet.highlights).containsExactly(20..25)
        assertThat(snippet.text.substring(20..25)).isEqualTo("report")
    }

    @Test
    fun `a long note is cut around the first match on word boundaries`() {
        val before = (1..30).joinToString(" ") { "word$it" }
        val after = (1..30).joinToString(" ") { "tail$it" }
        val note = "$before invoice $after"
        val match = note.indexOf("invoice").let { it until it + "invoice".length }

        val snippet = Snippets.around(note, listOf(match))

        assertThat(snippet.text).startsWith("…")
        assertThat(snippet.text).endsWith("…")
        assertThat(snippet.text.length).isAtMost(Snippets.MAX_LENGTH + 2)
        assertThat(snippet.highlights).hasSize(1)
        assertThat(snippet.text.substring(snippet.highlights.single())).isEqualTo("invoice")
        // Cut on word boundaries: no partial words at the edges.
        assertThat(snippet.text.removePrefix("…").substringBefore(' ')).matches("word\\d+")
        assertThat(snippet.text.removeSuffix("…").substringAfterLast(' ')).matches("tail\\d+")
    }

    @Test
    fun `a match at the start keeps the beginning of the note`() {
        val note = "Invoice " + (1..60).joinToString(" ") { "tail$it" }

        val snippet = Snippets.around(note, listOf(0..6))

        assertThat(snippet.text).startsWith("Invoice ")
        assertThat(snippet.text).endsWith("…")
        assertThat(snippet.highlights).containsExactly(0..6)
    }

    @Test
    fun `matches outside the excerpt are dropped`() {
        val note = "Invoice " + (1..60).joinToString(" ") { "tail$it" } + " invoice"
        val last = note.lastIndexOf("invoice").let { it until it + "invoice".length }

        val snippet = Snippets.around(note, listOf(0..6, last))

        assertThat(snippet.highlights).containsExactly(0..6)
    }
}
