package se.linusborjesson.scorespeaker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import se.linusborjesson.scorespeaker.settings.AppSettings
import se.linusborjesson.scorespeaker.ui.theme.ScoreSpeakerTheme

/** Desktop-harness previews of the History / Session-detail / Settings screens. */

@Composable
fun HistoryScreenPreview() = ScoreSpeakerTheme {
    HistoryScreen(
        state = HistoryUiState(
            summary = HistorySummaryUi(
                averageLabel = "9.72",
                trend = listOf(9.4f, 9.6f, 9.5f, 9.7f, 9.6f, 9.8f, 9.7f, 9.9f),
                sessionCount = 12,
                totalShots = 624,
                bestAverageLabel = "9.91",
            ),
            sessions = listOf(
                SessionRowUi(4, "TUE", "28", 60, "avg 9.84", null, listOf(9.7f, 9.8f, 9.6f, 9.9f, 9.8f, 10.0f), "590.4"),
                SessionRowUi(3, "FRI", "24", 60, "avg 9.71", "2 missed", listOf(9.5f, 9.6f, 9.8f, 9.7f, 9.6f), "582.6"),
                SessionRowUi(2, "SUN", "19", 40, "avg 9.66", null, listOf(9.4f, 9.7f, 9.6f, 9.8f), "386.4"),
                SessionRowUi(1, "TUE", "14", 60, "avg 9.58", null, listOf(9.3f, 9.5f, 9.6f, 9.5f, 9.7f), "574.8"),
            ),
        ),
        onSessionClick = {},
    )
}

@Composable
fun SessionDetailScreenPreview() = ScoreSpeakerTheme {
    val shots = listOf(
        TargetShot(0.03f, 0.05f, 10.4), TargetShot(-0.10f, 0.02f, 9.6), TargetShot(0.07f, 0.12f, 9.9),
        TargetShot(-0.04f, -0.08f, 10.1), TargetShot(0.12f, -0.04f, 9.5), TargetShot(-0.02f, 0.09f, 10.2),
        TargetShot(0.08f, 0.03f, 9.8), TargetShot(-0.07f, 0.11f, 9.7), TargetShot(0.01f, -0.02f, 10.6),
    )
    SessionDetailScreen(
        state = SessionDetailUiState(
            title = "Session 4",
            subtitle = "Tue 28 May · 10:14 · 24 min",
            totalLabel = "590.4", averageLabel = "9.84", bestLabel = "10.6", shotCount = 60,
            plotShots = shots,
            log = listOf(
                ShotLogRowUi(9, "10.6", 10.6, "Centre"),
                ShotLogRowUi(8, "9.7", 9.7, "Left 0.9"),
                ShotLogRowUi(7, "9.8", 9.8, "Low 0.4"),
                ShotLogRowUi(6, "10.2", 10.2, "High 0.3"),
                ShotLogRowUi(5, "9.5", 9.5, "Low-right 1.2"),
            ),
            missingNote = null,
        ),
        onBack = {},
    )
}

@Composable
fun SettingsScreenPreview() {
    var settings by remember { mutableStateOf(AppSettings()) }
    ScoreSpeakerTheme {
        SettingsScreen(
            settings = settings,
            onChange = { settings = it },
            keyBindings = listOf(
                KeyBindingRowUi("read_last_score", "Read last shot (score and error)", "NUMPAD 1", listening = false),
                KeyBindingRowUi("read_avg_error_5", "Read average error (last 5 shots)", null, listening = true),
            ),
        )
    }
}
