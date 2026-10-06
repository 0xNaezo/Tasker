package app.tasker.feature.capture

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureLogicTest {
    @Test
    fun `literal range moves with text typed before it`() {
        val draft = CaptureDraft(text = "отчёт завтра", literalRanges = listOf(6..11))
        val edited = draft.withText("сдать отчёт завтра")
        assertThat(edited.literalRanges).containsExactly(12..17)
        assertThat(edited.text.substring(12..17)).isEqualTo("завтра")
    }

    @Test
    fun `literal range stays when text is appended after it`() {
        val draft = CaptureDraft(text = "завтра отчёт", literalRanges = listOf(0..5))
        assertThat(draft.withText("завтра отчёт срочно").literalRanges).containsExactly(0..5)
    }

    @Test
    fun `editing inside a literal range drops it`() {
        val draft = CaptureDraft(text = "отчёт завтра", literalRanges = listOf(6..11))
        assertThat(draft.withText("отчёт завтро").literalRanges).isEmpty()
    }

    @Test
    fun `clearing the text starts a fresh draft`() {
        val draft = CaptureDraft(text = "отчёт завтра", literalRanges = listOf(6..11))
        assertThat(draft.withText("")).isEqualTo(CaptureDraft())
    }

    @Test
    fun `short shared text is parsed as is with its link`() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "Прочитать https://example.com/a завтра")
        val shared = SharedText.from(intent, "com.android.chrome")
        assertThat(shared).isEqualTo(
            SharedText("Прочитать https://example.com/a завтра", null, "com.android.chrome", "https://example.com/a"),
        )
    }

    @Test
    fun `subject is joined with a bare link`() {
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_SUBJECT, "Article")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/post")
        assertThat(SharedText.from(intent, null)?.input).isEqualTo("Article https://example.com/post")
    }

    @Test
    fun `long shared text keeps the first line as input and the rest as note`() {
        val body = "First line\n" + "x".repeat(300)
        val shared = SharedText.from(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, body), null)
        assertThat(shared?.input).isEqualTo("First line")
        assertThat(shared?.note).isEqualTo(body)
    }

    @Test
    fun `empty share is ignored`() {
        assertThat(SharedText.from(Intent(Intent.ACTION_SEND), null)).isNull()
        assertThat(SharedText.from(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "x"), null)).isNull()
    }

    @Test
    fun `links are shortened for chips`() {
        assertThat(shortUrl("https://www.example.com")).isEqualTo("example.com")
        assertThat(shortUrl("https://github.com/org")).isEqualTo("github.com/org")
        assertThat(shortUrl("https://github.com/org/repo/issues/42")).isEqualTo("github.com/…/42")
    }
}
