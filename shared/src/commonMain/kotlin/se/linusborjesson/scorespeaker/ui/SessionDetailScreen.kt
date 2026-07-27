package se.linusborjesson.scorespeaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme

data class ShotLogRowUi(
    val shotNumber: Int,
    val scoreLabel: String,      // "10.2" / "—"
    val score: Double?,          // drives the tick color
    val note: String,            // "Low 0.4" / "Centre" / "—"
)

data class SessionDetailUiState(
    val title: String,           // "Session 4"
    val subtitle: String,        // "Tue 28 May · 10:14 · 24 min"
    val totalLabel: String,
    val averageLabel: String,
    val bestLabel: String,
    val shotCount: Int,
    val plotShots: List<TargetShot>,
    val log: List<ShotLogRowUi>,
    val missingNote: String?,    // "2 shots missed (3, 4)"
)

/** Session detail — group plot + shot log, with header stats. */
@Composable
fun SessionDetailScreen(
    state: SessionDetailUiState,
    onBack: () -> Unit,
) {
    val c = ScoreTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val portrait = maxWidth < maxHeight
        Column(Modifier.fillMaxSize()) {
            // app bar — in portrait the stats drop to their own row under the
            // title; four stats beside a title don't fit a narrow screen.
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 18.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val backDescription = LocalStrings.current.backDescription
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button, onClick = onBack)
                        .semantics { contentDescription = backDescription },
                    contentAlignment = Alignment.Center,
                ) { Text("‹", style = ScoreTheme.type.titleLarge, color = c.textMid, modifier = Modifier.clearAndSetSemantics { }) }
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = ScoreTheme.type.title, color = c.textHi)
                    Text(state.subtitle, style = ScoreTheme.type.caption, color = c.textMid)
                }
                if (!portrait) HeaderStats(state)
            }
            if (portrait) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 8.dp)) {
                    HeaderStats(state)
                }
            }

            if (portrait) {
                Column(
                    Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GroupPlotCard(state, Modifier.fillMaxWidth(), portrait = true)
                    ShotLogCard(state, Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    GroupPlotCard(state, Modifier.fillMaxHeight(), portrait = false)
                    ShotLogCard(state, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun HeaderStats(state: SessionDetailUiState) {
    val c = ScoreTheme.colors
    val s = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        HeaderStat(s.statTotal, state.totalLabel, c.good)
        HeaderStat(s.statAvg, state.averageLabel, c.textHi)
        HeaderStat(s.statBest, state.bestLabel, c.textHi)
        HeaderStat(s.statShots, "${state.shotCount}", c.textHi)
    }
}

@Composable
private fun GroupPlotCard(state: SessionDetailUiState, modifier: Modifier, portrait: Boolean) {
    val c = ScoreTheme.colors
    InstrumentCard(modifier) {
        Column(
            // Landscape: wrap width, center vertically in the full-height
            // card. Portrait: span the width, natural height.
            Modifier.padding(14.dp).let { if (portrait) it.fillMaxWidth() else it.fillMaxHeight() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TargetDiagram(size = 200.dp, shots = state.plotShots, emphasizedIndex = -1)
            Spacer(Modifier.height(8.dp))
            Eyebrow(LocalStrings.current.shotGroup)
            state.missingNote?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = ScoreTheme.type.caption, color = c.guide)
            }
        }
    }
}

@Composable
private fun ShotLogCard(state: SessionDetailUiState, modifier: Modifier) {
    val c = ScoreTheme.colors
    InstrumentCard(modifier) {
        Column(Modifier.fillMaxSize()) {
            Eyebrow(LocalStrings.current.shotLog, modifier = Modifier.padding(start = 16.dp, top = 13.dp, bottom = 4.dp))
            if (state.log.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(LocalStrings.current.noShots, style = ScoreTheme.type.caption, color = c.textMid)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.log) { ShotLogRow(it) }
                }
            }
        }
    }
}

@Composable
private fun HeaderStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, style = ScoreTheme.type.stat, color = color)
        Eyebrow(label)
    }
}

@Composable
private fun ShotLogRow(row: ShotLogRowUi) {
    val c = ScoreTheme.colors
    val tick = when {
        row.score == null -> c.textDim
        row.score >= 9.0 -> c.good
        row.score >= 7.5 -> c.guide
        else -> c.bad
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${row.shotNumber}", style = ScoreTheme.type.hud, color = c.textDim, modifier = Modifier.width(22.dp))
        Box(Modifier.size(width = 4.dp, height = 20.dp).clip(RoundedCornerShape(2.dp)).background(tick))
        Text(row.scoreLabel, style = ScoreTheme.type.stat, color = c.textHi, modifier = Modifier.width(50.dp))
        Text(row.note, style = ScoreTheme.type.caption, color = c.textMid)
    }
}
