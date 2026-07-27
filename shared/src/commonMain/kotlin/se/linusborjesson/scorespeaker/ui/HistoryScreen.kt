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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme

/** 30-day-ish summary block on the history screen. */
data class HistorySummaryUi(
    val averageLabel: String,        // "9.72" / "—"
    val trend: List<Float>,          // recent session averages, for the sparkline
    val sessionCount: Int,
    val totalShots: Int,
    val bestAverageLabel: String,    // best session average
)

/** One row in the sessions list. Dates are pre-formatted by the platform. */
data class SessionRowUi(
    val index: Int,
    val dayLabel: String,            // "TUE"
    val dateLabel: String,           // "28"
    val shotCount: Int,
    val averageLabel: String,        // "avg 9.84"
    val gapNote: String?,            // "2 missed" or null
    val trend: List<Float>,          // this session's shot scores
    val totalLabel: String,          // "590.4"
)

data class HistoryUiState(
    val summary: HistorySummaryUi?,
    val sessions: List<SessionRowUi>,
)

/**
 * History — a 30-day summary card beside a sessions list. Platform-neutral;
 * the Android layer formats dates and supplies [HistoryUiState].
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onSessionClick: (Int) -> Unit,
) {
    val c = ScoreTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Text(
            LocalStrings.current.historyTitle,
            style = ScoreTheme.type.titleLarge, color = c.textHi,
            modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 10.dp),
        )
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
            if (maxWidth < maxHeight) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard(state.summary, Modifier.fillMaxWidth().height(250.dp))
                    SessionsList(state, onSessionClick, Modifier.weight(1f))
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SummaryCard(state.summary, Modifier.width(282.dp).fillMaxHeight())
                    SessionsList(state, onSessionClick, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun SessionsList(state: HistoryUiState, onSessionClick: (Int) -> Unit, modifier: Modifier) {
    val c = ScoreTheme.colors
    val s = LocalStrings.current
    Column(modifier) {
        Eyebrow(s.sessions, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        if (state.sessions.isEmpty()) {
            InstrumentCard(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        s.noSessionsYet,
                        style = ScoreTheme.type.caption, color = c.textMid, textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            InstrumentCard(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.sessions) { s -> SessionRow(s, onSessionClick) }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: HistorySummaryUi?, modifier: Modifier) {
    val c = ScoreTheme.colors
    val s = LocalStrings.current
    InstrumentCard(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Eyebrow(s.lastThirtyDays)
            if (summary == null) {
                Spacer(Modifier.height(8.dp))
                Text("—", style = ScoreTheme.type.titleLarge, color = c.textMid)
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(summary.averageLabel, style = ScoreTheme.type.aimValue, color = c.textHi)
                Spacer(Modifier.width(8.dp))
                Text(s.avgPerShot, style = ScoreTheme.type.caption, color = c.textMid, modifier = Modifier.padding(bottom = 5.dp))
            }
            if (summary.trend.size >= 2) {
                Spacer(Modifier.height(10.dp))
                Sparkline(summary.trend, width = 160.dp, height = 36.dp)
            }
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatRow(s.sessions, "${summary.sessionCount}")
                StatRow(s.bestSessionAvg, summary.bestAverageLabel)
                StatRow(s.totalShots, "${summary.totalShots}")
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val c = ScoreTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Eyebrow(label)
        Spacer(Modifier.weight(1f))
        Text(value, style = ScoreTheme.type.stat, color = c.textHi)
    }
}

@Composable
private fun SessionRow(s: SessionRowUi, onClick: (Int) -> Unit) {
    val c = ScoreTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onClick(s.index) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.width(42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.dayLabel, style = ScoreTheme.type.hud, color = c.textDim)
            Text(s.dateLabel, style = ScoreTheme.type.stat, color = c.textHi)
        }
        Column(Modifier.weight(1f)) {
            Text(
                LocalStrings.current.shotsCountTemplate.format(s.shotCount),
                style = ScoreTheme.type.body, color = c.textHi, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(s.averageLabel, style = ScoreTheme.type.caption, color = c.textMid)
                s.gapNote?.let {
                    Text(" · $it", style = ScoreTheme.type.caption, color = c.guide)
                }
            }
        }
        if (s.trend.size >= 2) Sparkline(s.trend, width = 64.dp, height = 22.dp, color = c.textMid)
        Text(s.totalLabel, style = ScoreTheme.type.stat, color = c.textHi)
    }
}
