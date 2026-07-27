package se.linusborjesson.scorespeaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme

/** Brand cyan from art/score-speaker-icon.svg (dark backgrounds). */
val BrandAccent = Color(0xFF1FC7DD)

/** Brand off-white (ring color on dark backgrounds). */
val BrandInk = Color(0xFFFAF8F4)

/** Light-background variants, from art/score-speaker-mark-dark.svg. */
val BrandAccentOnLight = Color(0xFF0C8FA1)
val BrandInkOnLight = Color(0xFF101A1D)

/**
 * The Score Speaker mark — a target ring with bullseye plus two sound
 * waves — drawn in code so every platform renders it from the same
 * geometry as `art/score-speaker-mark-*.svg` without image resources.
 *
 * The mark is sized to fit the smaller box dimension and centered.
 * Defaults follow the active theme, matching the SVG mark-light/mark-dark
 * variants.
 */
@Composable
fun ScoreSpeakerMark(
    modifier: Modifier = Modifier,
    ring: Color = if (ScoreTheme.colors.isDark) BrandInk else BrandInkOnLight,
    accent: Color = if (ScoreTheme.colors.isDark) BrandAccent else BrandAccentOnLight,
) {
    Canvas(modifier) {
        // Source geometry lives in the SVG's 100-unit space; its ink spans
        // x 16.75..95.25, y 13.85..86.15 (stroke included). Scale that
        // bounding box into the canvas and center it.
        val s = minOf(size.width / 78.5f, size.height / 72.3f)
        val cx = size.width / 2 + (45f - 56f) * s // 56 = mark bbox center x
        val cy = size.height / 2
        val center = Offset(cx, cy)
        val stroke = Stroke(width = 4.5f * s, cap = StrokeCap.Round)

        drawCircle(ring, radius = 26f * s, center = center, style = Stroke(4.5f * s))
        drawCircle(accent, radius = 8f * s, center = center)
        for (r in listOf(38f, 48f)) {
            drawArc(
                color = accent,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - r * s, cy - r * s),
                size = Size(2 * r * s, 2 * r * s),
                style = stroke,
            )
        }
    }
}
