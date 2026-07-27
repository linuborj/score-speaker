package se.linusborjesson.scorespeaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme

/** Minimal trend polyline. Auto-scales to its data's min/max. */
@Composable
fun Sparkline(
    data: List<Float>,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    height: Dp = 24.dp,
    color: Color? = null,
) {
    val stroke = color ?: ScoreTheme.colors.good
    Canvas(modifier.size(width, height)) {
        if (data.size < 2) return@Canvas
        val min = data.min()
        val max = data.max()
        val rng = (max - min).takeIf { it != 0f } ?: 1f
        val pad = size.height * 0.08f
        val path = Path()
        data.forEachIndexed { i, d ->
            val x = i / (data.size - 1f) * size.width
            val y = size.height - ((d - min) / rng) * (size.height - 2 * pad) - pad
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, stroke, style = Stroke(width = size.height * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
