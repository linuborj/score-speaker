package se.linusborjesson.scorespeaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.ui.theme.ScoreColors
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme

/**
 * One shot on the schematic target. [x]/[y] are normalized so 1.0 = the outer
 * edge (ring 1), with **y up positive** (image-flipped on draw). [value] is the
 * ring score (drives the emphasis color).
 */
data class TargetShot(val x: Float, val y: Float, val value: Double)

private fun ringColorFor(v: Double, c: ScoreColors): Color = when {
    v >= 9.0 -> c.good
    v >= 7.5 -> c.guide
    else -> c.bad
}

/**
 * Schematic 10m air-rifle target — Compose port of the design's `TargetDiagram`
 * SVG. Ring radii follow ISSF spacing (ring 1 outer → centre). Draws faint
 * rings + crosshair, prior shots dim, and the emphasized [emphasizedIndex]
 * shot (defaults to the last).
 */
@Composable
fun TargetDiagram(
    modifier: Modifier = Modifier,
    size: Dp = 230.dp,
    shots: List<TargetShot> = emptyList(),
    emphasizedIndex: Int = -1,
) {
    val c = ScoreTheme.colors
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = s * 0.46f
        fun ringR(n: Int) = ((45.5f - 5f * (n - 1)) / 45.5f) * r
        fun px(x: Float, y: Float) = Offset(cx + x * r, cy - y * r)

        // backdrop disc
        drawCircle(c.surface1, radius = r + s * 0.02f, center = Offset(cx, cy))
        drawCircle(c.line, radius = r + s * 0.02f, center = Offset(cx, cy), style = Stroke(width = s * 0.005f))
        // faint bull fill (rings 4..in)
        drawCircle(Color.White.copy(alpha = 0.022f), radius = ringR(4), center = Offset(cx, cy))
        // rings 1..9
        for (n in 1..9) {
            drawCircle(
                color = (if (n <= 4) c.line2 else c.line).copy(alpha = 0.5f + 0.05f * n),
                radius = ringR(n), center = Offset(cx, cy),
                style = Stroke(width = if (n == 1) s * 0.005f else s * 0.004f),
            )
        }
        // 10-ring
        drawCircle(c.line2, radius = ringR(10) + s * 0.0125f, center = Offset(cx, cy), style = Stroke(width = s * 0.004f))
        // crosshair
        drawLine(c.line.copy(alpha = 0.5f), Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = s * 0.003f)
        drawLine(c.line.copy(alpha = 0.5f), Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = s * 0.003f)

        val lastIdx = if (emphasizedIndex < 0) shots.lastIndex else emphasizedIndex

        // prior shots (dim)
        shots.forEachIndexed { i, shot ->
            if (i == lastIdx) return@forEachIndexed
            drawCircle(c.textMid.copy(alpha = 0.55f), radius = s * 0.013f, center = px(shot.x, shot.y))
        }

        // emphasized shot
        shots.getOrNull(lastIdx)?.let { shot ->
            val p = px(shot.x, shot.y)
            val col = ringColorFor(shot.value, c)
            drawCircle(col.copy(alpha = 0.45f), radius = s * 0.045f, center = p, style = Stroke(width = s * 0.007f))
            drawCircle(col, radius = s * 0.021f, center = p)
            drawCircle(c.bg, radius = s * 0.021f, center = p, style = Stroke(width = s * 0.005f))
        }
    }
}
