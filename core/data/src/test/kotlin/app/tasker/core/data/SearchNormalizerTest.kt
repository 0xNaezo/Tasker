package app.tasker.core.data

import app.tasker.core.data.search.SearchNormalizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchNormalizerTest {
    /** Mirrors FTS prefix matching: every query term is a prefix of some indexed form. */
    private fun matches(text: String, query: String): Boolean {
        val forms = SearchNormalizer.indexText(text).split(' ')
        val terms = SearchNormalizer.tokens(query).map { it.form }
        return terms.isNotEmpty() && terms.all { term -> forms.any { it.startsWith(term) } }
    }

    @Test
    fun `russian word forms match through the snowball stem`() {
        assertThat(matches("Сдать отчёты клиенту", "отчет")).isTrue()
        assertThat(matches("сдать отчет", "отчёты")).isTrue()
        assertThat(matches("позвонить маме", "позвонил")).isTrue()
        assertThat(matches("купить молоко", "хлеб")).isFalse()
    }

    @Test
    fun `english word forms match`() {
        assertThat(matches("Write quarterly reports", "report")).isTrue()
        assertThat(matches("calling the bank", "call")).isTrue()
        assertThat(matches("don't forget", "dont")).isTrue()
    }

    @Test
    fun `ukrainian word forms match through the light stemmer and prefixes`() {
        assertThat(matches("Підготувати зустріч", "зустрічі")).isTrue()
        assertThat(matches("написати п'ять листів", "лист")).isTrue()
        assertThat(matches("перевірити звіти", "звіт")).isTrue()
    }

    @Test
    fun `match query uses prefixes and has no operators`() {
        assertThat(SearchNormalizer.matchQuery("Отчёт OR клиенту")).isEqualTo("отчет* or* клиент*")
        assertThat(SearchNormalizer.matchQuery("  !!! ")).isNull()
    }

    @Test
    fun `highlights point to the original text`() {
        val text = "Сдать отчёт клиенту"
        val ranges = SearchNormalizer.highlights(text, "отчеты")
        assertThat(ranges.map { text.substring(it) }).containsExactly("отчёт")
    }
}
