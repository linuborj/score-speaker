package se.linusborjesson.scorespeaker.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme

/**
 * Pill toggle — mint when on, matching the design's `.toggle`.
 *
 * Pass [onChange] = null to render it as a purely decorative indicator with
 * no semantics of its own — for rows that are themselves `toggleable` (the
 * row then carries the switch role and label for TalkBack, and the pill
 * doesn't show up as a second focusable element).
 */
@Composable
fun Toggle(on: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val c = ScoreTheme.colors
    val knobX by animateDpAsState(if (on) 23.dp else 3.dp, label = "knob")
    Box(
        modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (on) c.good else c.surface3)
            .let { m ->
                if (onChange != null) {
                    m.toggleable(value = on, role = Role.Switch, onValueChange = onChange)
                } else {
                    m.semantics { } // decorative; parent row owns the semantics
                }
            },
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 3.dp)
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (on) Color.White else c.textMid),
        )
    }
}

/** Segmented control — active segment raised, matching `.seg`. Announced as a radio group. */
@Composable
fun Segmented(options: List<String>, active: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = ScoreTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(11.dp)).background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(11.dp)).padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { o ->
            val selected = o == active
            Box(
                Modifier.weight(1f).height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) c.surface3 else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = ScoreTheme.type.caption, color = if (selected) c.textHi else c.textMid)
            }
        }
    }
}

/**
 * Horizontal slider — mint fill + white thumb. [value] in 0..1; tap to set.
 *
 * [label] names the control for screen readers; [valueDescription] replaces
 * the raw percentage TalkBack would otherwise announce (e.g. "1.4×"). The
 * `setProgress` semantics action makes it adjustable with TalkBack
 * volume-style gestures, not just tap-to-set.
 */
@Composable
fun Slider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueDescription: String? = null,
) {
    val c = ScoreTheme.colors
    var widthPx by remember { mutableStateOf(1) }
    Box(
        modifier.fillMaxWidth().height(22.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onChange((offset.x / widthPx).coerceIn(0f, 1f)) }
            }
            .semantics {
                if (label != null) contentDescription = label
                if (valueDescription != null) stateDescription = valueDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                setProgress { target ->
                    onChange(target.coerceIn(0f, 1f))
                    true
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surface3))
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.good))
    }
}
