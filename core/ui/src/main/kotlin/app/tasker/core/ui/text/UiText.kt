package app.tasker.core.ui.text

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** Text produced outside Compose (e.g. in a ViewModel) and resolved on screen in the current language. */
@Immutable
sealed interface UiText {
    data class Raw(val value: String) : UiText

    class Res(@param:StringRes val id: Int, vararg val args: Any) : UiText

    class Plural(@param:PluralsRes val id: Int, val count: Int, vararg val args: Any) : UiText
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> stringResource(id, *args.map { it.resolveArg() }.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, count, *args.map { it.resolveArg() }.toTypedArray())
}

@Composable
private fun Any.resolveArg(): Any = if (this is UiText) asString() else this
