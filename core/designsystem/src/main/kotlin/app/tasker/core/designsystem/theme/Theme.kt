package app.tasker.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale used across screens. */
@Immutable
data class Spacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/**
 * App theme: follows the system light/dark setting (§14.4). Text uses `sp` everywhere, so it scales up to 200 %;
 * Material components keep the 48 dp minimum touch target.
 */
@Composable
fun TaskerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkTaskerColors else LightTaskerColors
    CompositionLocalProvider(
        LocalTaskerColors provides colors,
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = Typography(),
            content = content,
        )
    }
}

/** Accessors for the app tokens: `TaskerTheme.colors.overdue`, `TaskerTheme.spacing.l`. */
object TaskerTheme {
    val colors: TaskerColors
        @Composable @ReadOnlyComposable
        get() = LocalTaskerColors.current

    val spacing: Spacing
        @Composable @ReadOnlyComposable
        get() = LocalSpacing.current
}

/** The app's colours for surfaces outside Compose UI, such as Glance widgets. */
object TaskerColorSchemes {
    val light: ColorScheme get() = LightScheme
    val dark: ColorScheme get() = DarkScheme
    val lightTokens: TaskerColors get() = LightTaskerColors
    val darkTokens: TaskerColors get() = DarkTaskerColors
}
