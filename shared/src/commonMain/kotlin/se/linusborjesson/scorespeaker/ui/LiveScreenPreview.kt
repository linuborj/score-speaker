package se.linusborjesson.scorespeaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme

/**
 * Desktop-harness preview of [LiveScreen] with a mocked camera surface (a SIUS
 * monitor seen head-on). For eyeballing the composed layout without a device;
 * the real Android screen injects a CameraX preview instead.
 */
@Composable
fun LiveScreenPreview() {
    val state = LiveScreenState(
        hasPermission = true,
        locked = true,
        overlay = CvOverlay(
            corners = listOf(360f to 150f, 1180f to 190f, 1150f to 880f, 330f to 840f),
            analyzerWidth = 1920, analyzerHeight = 1080, method = "MultiScaleMatcher",
        ),
        shotNumber = 24,
        score = "10.2",
        offsetXRings = 0.1,
        offsetYRings = -0.4,
    )
    LiveScreen(
        state = state,
        onHistoryClick = {}, onSettingsClick = {},
        camera = { MockSiusMonitor() },
    )
}

/** A fake SIUS monitor, head-on — stands in for the CameraX preview in previews. */
@Composable
private fun MockSiusMonitor() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(360.dp).size(width = 360.dp, height = 210.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0C1623)),
        ) {
            Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                TargetDiagram(size = 130.dp, shots = listOf(TargetShot(-0.12f, -0.18f, 9.8)))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("10.2", style = ScoreTheme.type.aimValue, color = Color(0xFF62B6F0))
                    listOf("9.8", "10.4", "9.7").forEach {
                        Text(it, style = ScoreTheme.type.hud, color = Color(0xFF2F5070))
                    }
                }
            }
        }
    }
}
