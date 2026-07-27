package se.linusborjesson.scorespeaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.ui.theme.ScoreSpeakerTheme
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme
import kotlin.math.hypot

/** A detected quad in analyzer-frame pixels, for the CV overlay. Platform-neutral. */
data class CvOverlay(
    val corners: List<Pair<Float, Float>>,
    val analyzerWidth: Int,
    val analyzerHeight: Int,
    val method: String,
    /** Detected green-dot position in analyzer-frame px (debug only). */
    val shotDot: Pair<Float, Float>? = null,
    /** Estimated target centre in analyzer-frame px (default overlay). */
    val targetCenter: Pair<Float, Float>? = null,
    /** Cell D shot-index sub-region quad in analyzer-frame px (debug only). */
    val dShotRegion: List<Pair<Float, Float>>? = null,
)

/**
 * Pipeline internals shown when the debug-overlay setting is on: detection
 * HUD plus the actual cell crop the reader ran on and what it read.
 * Platform-neutral — cell crops arrive as [ImageBitmap]s.
 */
data class DebugPanelState(
    val detectionLabel: String,
    val fps: Double,
    val cacheLine: String,
    val dCell: ImageBitmap?,
    val dText: String?,
    /** Binarized shot crop exactly as the reader sees it. */
    val dShotProcessed: ImageBitmap? = null,
    /** Formatted glyph-confidence line, e.g. "glyph 62%". */
    val dConfidence: String? = null,
)

/** Display snapshot for [LiveScreen]. Platform-neutral — no Android types. */
data class LiveScreenState(
    val hasPermission: Boolean = true,
    /** Rear camera missing or failed to bind — permanent, unlike a denial. */
    val cameraUnavailable: Boolean = false,
    val locked: Boolean = false,
    val overlay: CvOverlay? = null,
    val shotNumber: Int? = null,
    val score: String? = null,
    // Last shot's centre offset in ring units. X positive = right; Y positive
    // = up (image +down is flipped for display, matching the target diagram).
    val offsetXRings: Double? = null,
    val offsetYRings: Double? = null,
    val debug: DebugPanelState? = null,
)

/**
 * The live screen — a full-bleed camera surface with the detection overlay,
 * nav chips (History / Settings), and a bottom readout showing
 * the last shot's number, score, and X/Y error. The camera surface is injected
 * via [camera] (Android passes a CameraX `PreviewView`; the desktop harness
 * passes a mock), so this layout is platform-neutral and previewable anywhere.
 */
@Composable
fun LiveScreen(
    state: LiveScreenState,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    camera: @Composable BoxScope.() -> Unit,
    /** Manual test-case capture, offered on the debug panel. */
    onCaptureClick: () -> Unit = {},
    /**
     * Pinch-zoom gesture: called with the multiplicative factor of each
     * gesture step (the caller owns clamping and persistence). Null
     * disables the gesture (desktop previews).
     */
    onZoomGesture: ((Float) -> Unit)? = null,
) {
    // Always dark: this is chrome over a live camera feed, regardless of the
    // system theme the rest of the app follows.
    ScoreSpeakerTheme(darkTheme = true) {
        val c = ScoreTheme.colors
        val s = LocalStrings.current
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF04060A))) {
            val portrait = maxWidth < maxHeight
            // The camera surface stays full-bleed edge-to-edge; only the
            // overlay chrome respects the system-bar / cutout insets.
            when {
                state.cameraUnavailable -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.cameraUnavailable, style = ScoreTheme.type.body, color = c.textMid)
                }
                state.hasPermission -> camera()
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.cameraPermissionRequired, style = ScoreTheme.type.body, color = c.textMid)
                }
            }

            DetectionOverlay(state.overlay)

            // Pinch-to-zoom layer over the camera feed. Sits under the nav
            // chips and readout (later children draw on top and win their
            // own hit tests), so taps keep working. The Settings slider is
            // the accessible route to the same value.
            onZoomGesture?.let { onZoom ->
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) onZoom(zoom)
                        }
                    },
                )
            }

            Column(
                Modifier.align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Live region: aiming the camera is the one task the end user
                // must do without seeing the screen — speak lock transitions.
                Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (state.locked) Chip(s.locked, tone = ChipTone.Good, leading = { StatusDot(c.good) }, solid = true)
                    else Chip(s.searching, tone = ChipTone.Bad, leading = { StatusDot(c.bad) }, solid = true)
                }
                state.debug?.let { DebugPanel(it, onCaptureClick) }
            }

            // Portrait is too narrow for the status chip plus the nav chips
            // in one line — stack the nav chips down the right edge instead.
            val navChips: @Composable () -> Unit = {
                Box(Modifier.clickable(role = Role.Button, onClick = onHistoryClick)) { Chip(s.historyChip, tone = ChipTone.Neutral, solid = true) }
                Box(Modifier.clickable(role = Role.Button, onClick = onSettingsClick)) { Chip(s.settingsChip, tone = ChipTone.Neutral, solid = true) }
            }
            val navModifier = Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
            if (portrait) {
                Column(navModifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) { navChips() }
            } else {
                Row(navModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) { navChips() }
            }

            ReadoutBar(state, Modifier.align(Alignment.BottomStart))
        }
    }
}

/**
 * Debug card under the lock chip: detection method/confidence, fps, read
 * cache hit rate, and the live Cell D crop with the value parsed from it —
 * enough to tell a bad crop (geometry) from a bad read (matcher) in the
 * field.
 */
@Composable
private fun DebugPanel(debug: DebugPanelState, onCaptureClick: () -> Unit) {
    val c = ScoreTheme.colors
    Column(
        Modifier
            .background(Color(0xD9080B12))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(debug.detectionLabel, style = ScoreTheme.type.caption, color = c.textHi)
        Text(
            "${"%.1f".format(debug.fps)} fps   ${debug.cacheLine}",
            style = ScoreTheme.type.caption, color = c.textMid,
        )
        DebugCellRow("D", debug.dCell, debug.dText)
        // The preprocessed crop the reader actually reads — bad binarization
        // (eaten strokes, phantom ink) shows here even when the raw cell
        // above looks fine.
        debug.dShotProcessed?.let { DebugImageRow("shot", it) }
        debug.dConfidence?.let { Text(it, style = ScoreTheme.type.caption, color = c.textMid) }
        Box(Modifier.clickable(role = Role.Button, onClick = onCaptureClick)) {
            Chip(LocalStrings.current.captureChip, tone = ChipTone.Info)
        }
    }
}

@Composable
private fun DebugImageRow(label: String, image: ImageBitmap) {
    val c = ScoreTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = ScoreTheme.type.caption, color = c.textDim)
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.height(28.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun DebugCellRow(label: String, cell: ImageBitmap?, text: String?) {
    val c = ScoreTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = ScoreTheme.type.caption, color = c.textDim)
        if (cell != null) {
            Image(
                bitmap = cell,
                contentDescription = null,
                modifier = Modifier.height(36.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("—", style = ScoreTheme.type.caption, color = c.textDim)
        }
        Text(text ?: "∅", style = ScoreTheme.type.caption, color = if (text != null) c.good else c.bad)
    }
}

/** Centre reticle + the detected screen quad (corner-emphasised edges). */
@Composable
private fun DetectionOverlay(overlay: CvOverlay?) {
    val c = ScoreTheme.colors
    Canvas(Modifier.fillMaxSize()) {
        // center reticle
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arm = 7.dp.toPx()
        val gap = 5.dp.toPx()
        val reticle = Color.White.copy(alpha = 0.5f)
        drawLine(reticle, Offset(cx, cy - gap - arm), Offset(cx, cy - gap), strokeWidth = 1.5f)
        drawLine(reticle, Offset(cx, cy + gap), Offset(cx, cy + gap + arm), strokeWidth = 1.5f)
        drawLine(reticle, Offset(cx - gap - arm, cy), Offset(cx - gap, cy), strokeWidth = 1.5f)
        drawLine(reticle, Offset(cx + gap, cy), Offset(cx + gap + arm, cy), strokeWidth = 1.5f)

        val q = overlay
        if (q != null && q.analyzerWidth > 0 && q.analyzerHeight > 0) {
            val scale = maxOf(size.width / q.analyzerWidth, size.height / q.analyzerHeight)
            val offX = (size.width - q.analyzerWidth * scale) / 2f
            val offY = (size.height - q.analyzerHeight * scale) / 2f
            fun toView(p: Pair<Float, Float>) = Offset(p.first * scale + offX, p.second * scale + offY)
            val pts = q.corners.map { (x, y) -> toView(x to y) }
            // Corner brackets only, no connecting edges — the quad draws
            // over camera imagery and full edges compete with the display's
            // own bright frame. Same amber as the centre cross (c.guide);
            // the bright mint/blue signal tokens sat too close to the
            // display's frame color. (Detection method — refined vs cold —
            // is in the debug HUD; it doesn't color-code the quad.)
            val color = c.guide
            val len = 18.dp.toPx()
            for (i in pts.indices) {
                val p = pts[i]
                for (nb in listOf(pts[(i + 1) % pts.size], pts[(i + 3) % pts.size])) {
                    val d = hypot((nb.x - p.x).toDouble(), (nb.y - p.y).toDouble()).toFloat().coerceAtLeast(1f)
                    val t = (len / d).coerceAtMost(0.45f)
                    drawLine(color, p, Offset(p.x + (nb.x - p.x) * t, p.y + (nb.y - p.y) * t), strokeWidth = 6f)
                }
            }

            // Estimated target centre — part of the default overlay, same
            // amber as the corner brackets: live setup feedback that the
            // geometry chain points where the helper thinks.
            q.targetCenter?.let { tc ->
                val p = toView(tc)
                val arm2 = 7.dp.toPx()
                drawLine(c.guide, Offset(p.x - arm2, p.y), Offset(p.x + arm2, p.y), strokeWidth = 2.dp.toPx())
                drawLine(c.guide, Offset(p.x, p.y - arm2), Offset(p.x, p.y + arm2), strokeWidth = 2.dp.toPx())
            }

            // Debug markers (populated only with the debug overlay on):
            // ring around the detected shot dot — a live check that the
            // geometry chain (rectify → cell → dot → back to frame) lines
            // up with what the camera actually sees.
            q.shotDot?.let { dot ->
                drawCircle(
                    color = c.good,
                    radius = 9.dp.toPx(),
                    center = toView(dot),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            // Cell D shot-index sub-region (red). Drawn from the extractor's
            // own rect function, so what you see is exactly what the glyph
            // reader crops; use to validate the split against the real
            // display's layout.
            fun drawQuad(quad: List<Pair<Float, Float>>, color: Color) {
                val p = quad.map(::toView)
                for (i in p.indices) {
                    drawLine(color, p[i], p[(i + 1) % p.size], strokeWidth = 1.5.dp.toPx())
                }
            }
            q.dShotRegion?.let { drawQuad(it, c.bad) }
        }
    }
}

/** Bottom strip over a scrim: SHOT n · SCORE · X · Y for the last shot. */
@Composable
private fun ReadoutBar(state: LiveScreenState, modifier: Modifier) {
    val c = ScoreTheme.colors
    val s = LocalStrings.current
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEB04060A))))
            // Scrim bleeds behind the transparent system bar; the readout
            // content stays clear of it.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Readout(s.readoutShot, state.shotNumber?.toString() ?: "—")
            Readout(s.readoutScore, state.score ?: "—", big = true, color = c.good)
            Readout("X", state.offsetXRings?.let { "%+.1f".format(it) } ?: "—")
            Readout("Y", state.offsetYRings?.let { "%+.1f".format(it) } ?: "—")
        }
    }
}

@Composable
private fun Readout(label: String, value: String, big: Boolean = false, color: Color? = null) {
    val c = ScoreTheme.colors
    Column {
        Eyebrow(label, color = c.textDim)
        Text(
            value,
            style = if (big) ScoreTheme.type.aimValue else ScoreTheme.type.stat,
            color = color ?: c.textHi,
        )
    }
}
