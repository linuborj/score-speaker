package se.linusborjesson.scorespeaker.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme

/** UPPERCASE tracked label. The design always renders eyebrows in caps. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text.uppercase(),
        style = ScoreTheme.type.eyebrow,
        color = color ?: ScoreTheme.colors.textMid,
        modifier = modifier,
    )
}

enum class ChipTone { Neutral, Good, Bad, Info }

/**
 * Fully-rounded pill. Tone drives fill + border + text per the design.
 *
 * [solid] backs the pill with a near-opaque dark scrim before the tone
 * tint — for chips floating over the live camera feed, where the default
 * translucent fills wash out against whatever the camera sees.
 */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Neutral,
    leading: @Composable (() -> Unit)? = null,
    solid: Boolean = false,
) {
    val c = ScoreTheme.colors
    val (fill, border, fg) = when (tone) {
        ChipTone.Neutral ->
            if (solid) Triple(Color.Transparent, c.line2, c.textHi)
            else Triple(Color.Transparent, c.line2, c.text)
        ChipTone.Good -> Triple(c.goodSoft, c.good.copy(alpha = 0.4f), c.good)
        ChipTone.Bad -> Triple(c.badSoft, c.bad.copy(alpha = 0.4f), c.bad)
        ChipTone.Info -> Triple(c.infoSoft, c.info.copy(alpha = 0.4f), c.info)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .let { if (solid) it.background(Color(0xE60B0E13)) else it }
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(text, style = ScoreTheme.type.caption, color = fg)
    }
}

/** The standard instrument card: surface-1 fill, 1px hairline, 16dp radius. */
@Composable
fun InstrumentCard(
    modifier: Modifier = Modifier,
    fill: Color? = null,
    border: Color? = null,
    content: @Composable () -> Unit,
) {
    val c = ScoreTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ScoreTheme.radii.card))
            .background(fill ?: c.surface1)
            .border(1.dp, border ?: c.line, RoundedCornerShape(ScoreTheme.radii.card)),
    ) { content() }
}

/**
 * Blinking status dot (LIVE / LOCKED / SEARCHING). ~1.4s blink, matching the
 * `rec-dot` keyframe. Pass [blink] = false for a steady dot.
 */
@Composable
fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp, blink: Boolean = true) {
    val a = if (blink) {
        val t = rememberInfiniteTransition(label = "blink")
        val v by t.animateFloat(
            initialValue = 1f, targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
            label = "blinkAlpha",
        )
        v
    } else 1f
    Box(Modifier.size(size).alpha(a).clip(CircleShape).background(color))
}
