package se.linusborjesson.scorespeaker.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier

/**
 * Convenience accessors so call sites read `ScoreTheme.colors.good` /
 * `ScoreTheme.type.bigScore` instead of touching the CompositionLocals.
 */
object ScoreTheme {
    val colors: ScoreColors
        @Composable @ReadOnlyComposable get() = LocalScoreColors.current
    val type: ScoreTypography
        @Composable @ReadOnlyComposable get() = LocalScoreTypography.current
    val radii: ScoreRadii
        @Composable @ReadOnlyComposable get() = LocalScoreRadii.current
}

/**
 * Root theme. Follows the system light/dark setting by default (pass
 * [darkTheme] to force one — the live camera screen forces dark). Provides
 * the instrument tokens via CompositionLocals and a thin Material 3 scheme
 * mapping (so stock components — sliders, ripple — land on-brand). Fills
 * the background with [ScoreColors.bg] by default.
 */
@Composable
fun ScoreSpeakerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colors: ScoreColors = if (darkTheme) darkScoreColors() else lightScoreColors(),
    typography: ScoreTypography = ScoreTypography(),
    radii: ScoreRadii = ScoreRadii(),
    fillBackground: Boolean = true,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val material = base.copy(
        primary = colors.good,
        onPrimary = colors.goodInk,
        secondary = colors.info,
        error = colors.bad,
        background = colors.bg,
        onBackground = colors.text,
        surface = colors.surface1,
        onSurface = colors.text,
        surfaceVariant = colors.surface2,
        outline = colors.line2,
    )
    CompositionLocalProvider(
        LocalScoreColors provides colors,
        LocalScoreTypography provides typography,
        LocalScoreRadii provides radii,
    ) {
        MaterialTheme(colorScheme = material) {
            if (fillBackground) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().background(colors.bg),
                ) { content() }
            } else {
                content()
            }
        }
    }
}
