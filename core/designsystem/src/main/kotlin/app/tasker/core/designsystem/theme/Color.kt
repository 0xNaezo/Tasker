package app.tasker.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens (tech plan §14.4). Red is reserved for the [TaskerColors.overdue] token (DAT-1): the Material error
 * colour is deliberately amber, so nothing else in the app turns red. The detekt rule `OverdueColorOnly` forbids
 * reading `colorScheme.error` outside this file and Theme.kt.
 */
@Immutable
data class TaskerColors(
    /** Overdue deadlines only. */
    val overdue: Color,
    val onOverdue: Color,
    val overdueContainer: Color,
    /** Overload of the day plan: a warning, not an error. */
    val overload: Color,
    val overloadContainer: Color,
    /** Marks of fields filled by AI (AI-4). */
    val ai: Color,
    val aiContainer: Color,
    /** "In review" marks. */
    val review: Color,
    val reviewContainer: Color,
    /** Completed work in the done log. */
    val done: Color,
    /** Capacity bar track and fill. */
    val capacityTrack: Color,
    val capacityFill: Color,
    /** Muted secondary text (reasons, metadata). */
    val muted: Color,
)

internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF1F6B5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F1DD),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8DF),
    onSecondaryContainer = Color(0xFF06201A),
    tertiary = Color(0xFF41627A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC7E7FF),
    onTertiaryContainer = Color(0xFF001E2E),
    error = Color(0xFF7F5300),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDDB3),
    onErrorContainer = Color(0xFF291800),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF181C1B),
    surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF181C1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F2),
    surfaceContainer = Color(0xFFEBEFEC),
    surfaceContainerHigh = Color(0xFFE5E9E6),
    surfaceContainerHighest = Color(0xFFE0E3E1),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C4),
    inverseSurface = Color(0xFF2D3130),
    inverseOnSurface = Color(0xFFEEF2EF),
    inversePrimary = Color(0xFF8CD5C1),
)

internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF8CD5C1),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFFA8F1DD),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1C352E),
    secondaryContainer = Color(0xFF334C44),
    onSecondaryContainer = Color(0xFFCCE8DF),
    tertiary = Color(0xFFA9CBE6),
    onTertiary = Color(0xFF0F3449),
    tertiaryContainer = Color(0xFF284A61),
    onTertiaryContainer = Color(0xFFC7E7FF),
    error = Color(0xFFFFB955),
    onError = Color(0xFF452B00),
    errorContainer = Color(0xFF633F00),
    onErrorContainer = Color(0xFFFFDDB3),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE0E3E1),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFE0E3E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF181C1B),
    surfaceContainer = Color(0xFF1C201F),
    surfaceContainerHigh = Color(0xFF262B29),
    surfaceContainerHighest = Color(0xFF313634),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
    inverseSurface = Color(0xFFE0E3E1),
    inverseOnSurface = Color(0xFF2D3130),
    inversePrimary = Color(0xFF1F6B5C),
)

internal val LightTaskerColors = TaskerColors(
    overdue = Color(0xFFC62828),
    onOverdue = Color(0xFFFFFFFF),
    overdueContainer = Color(0xFFFFDAD6),
    overload = Color(0xFF8A5A00),
    overloadContainer = Color(0xFFFFDDB3),
    ai = Color(0xFF6750A4),
    aiContainer = Color(0xFFEADDFF),
    review = Color(0xFF41627A),
    reviewContainer = Color(0xFFC7E7FF),
    done = Color(0xFF2E6B3A),
    capacityTrack = Color(0xFFDBE5E0),
    capacityFill = Color(0xFF1F6B5C),
    muted = Color(0xFF5B6662),
)

internal val DarkTaskerColors = TaskerColors(
    overdue = Color(0xFFFF8A80),
    onOverdue = Color(0xFF5F0B07),
    overdueContainer = Color(0xFF8C1D18),
    overload = Color(0xFFFFB955),
    overloadContainer = Color(0xFF633F00),
    ai = Color(0xFFD0BCFF),
    aiContainer = Color(0xFF4F378B),
    review = Color(0xFFA9CBE6),
    reviewContainer = Color(0xFF284A61),
    done = Color(0xFF8FD69B),
    capacityTrack = Color(0xFF3F4945),
    capacityFill = Color(0xFF8CD5C1),
    muted = Color(0xFFA3ADA8),
)

val LocalTaskerColors = staticCompositionLocalOf { LightTaskerColors }
