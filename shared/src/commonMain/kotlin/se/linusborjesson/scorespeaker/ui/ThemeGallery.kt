package se.linusborjesson.scorespeaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.ui.theme.ScoreSpeakerTheme
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme

/**
 * A standalone gallery of the theme + primitives — for *seeing the vibe*, not
 * a product screen. Renders color swatches, the type scale, chips, the target
 * diagram, sparkline, and a small mock of the live-readout column so the
 * composed feel is visible. Wrap-free: provides its own [ScoreSpeakerTheme].
 */
@Composable
fun ThemeGallery() {
    ScoreSpeakerTheme {
        val c = ScoreTheme.colors
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Score Speaker — theme & primitives", style = ScoreTheme.type.titleLarge, color = c.textHi)

            Section("Signal colors") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Swatch("good", c.good); Swatch("guide", c.guide)
                    Swatch("bad", c.bad); Swatch("info", c.info)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Swatch("bg", c.bg); Swatch("surface-1", c.surface1)
                    Swatch("surface-2", c.surface2); Swatch("surface-3", c.surface3)
                    Swatch("line", c.line); Swatch("line-2", c.line2)
                }
            }

            Section("Type scale") {
                Text("10.2", style = ScoreTheme.type.bigScore, color = c.good)
                Text("1.6 rings", style = ScoreTheme.type.aimValue, color = c.textHi)
                Text("History", style = ScoreTheme.type.titleLarge, color = c.textHi)
                Text("Session 04", style = ScoreTheme.type.title, color = c.textHi)
                Text("590.4", style = ScoreTheme.type.stat, color = c.textHi)
                Text("Score after each shot", style = ScoreTheme.type.body, color = c.textHi)
                Text("Group trending low-left · last 5", style = ScoreTheme.type.caption, color = c.textMid)
                Eyebrow("Adjust aim", color = c.guide)
            }

            Section("Chips & status") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("LOCKED", tone = ChipTone.Good, leading = { StatusDot(c.good) })
                    Chip("SEARCHING…", tone = ChipTone.Bad, leading = { StatusDot(c.bad) })
                    Chip("Centre", tone = ChipTone.Good)
                    Chip("Low 0.4", tone = ChipTone.Neutral)
                    Chip("SCORE", tone = ChipTone.Info)
                }
            }

            Section("Target diagram") {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TargetDiagram(size = 180.dp, shots = SAMPLE_SHOTS)
                    TargetDiagram(size = 180.dp, shots = SAMPLE_SHOTS + TargetShot(0.04f, -0.05f, 10.2))
                }
            }

            Section("Sparkline") {
                Sparkline(listOf(94f, 96f, 95f, 97f, 96f, 98f, 97f, 99f), width = 120.dp, height = 36.dp)
            }

            Section("Live readout (mock)") { LiveReadoutMock() }
        }
    }
}

private val SAMPLE_SHOTS = listOf(
    TargetShot(0.02f, 0.08f, 10.1), TargetShot(-0.14f, -0.06f, 9.4),
    TargetShot(0.10f, 0.16f, 9.7), TargetShot(-0.05f, 0.12f, 10.3),
    TargetShot(0.16f, -0.10f, 9.2),
)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(title)
        content()
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(color))
        Spacer(Modifier.height(4.dp))
        Text(name, style = ScoreTheme.type.hud, color = ScoreTheme.colors.textMid)
    }
}

/** Compact stand-in for the Live "shot registered" readout column. */
@Composable
private fun LiveReadoutMock() {
    val c = ScoreTheme.colors
    InstrumentCard(Modifier.width(420.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TargetDiagram(size = 120.dp, shots = SAMPLE_SHOTS + TargetShot(0.04f, -0.05f, 10.2))
                Column {
                    Eyebrow("Shot 24")
                    Text("10.2", style = ScoreTheme.type.bigScore, color = c.good)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Low 0.4", tone = ChipTone.Neutral)
                    }
                }
            }
            // caption bar
            InstrumentCard(fill = c.surface2, border = c.line2) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(c.goodSoft))
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Speaking now", color = c.good)
                        Text("Ten point two. Just low of centre.", style = ScoreTheme.type.body, color = c.textHi)
                    }
                }
            }
        }
    }
}
