package se.linusborjesson.scorespeaker

import se.linusborjesson.scorespeaker.pipeline.HistoryShot
import se.linusborjesson.scorespeaker.pipeline.Session
import se.linusborjesson.scorespeaker.pipeline.averageSince
import se.linusborjesson.scorespeaker.pipeline.summarize
import se.linusborjesson.scorespeaker.ui.HistorySummaryUi
import se.linusborjesson.scorespeaker.ui.HistoryUiState
import se.linusborjesson.scorespeaker.ui.SessionDetailUiState
import se.linusborjesson.scorespeaker.ui.SessionRowUi
import se.linusborjesson.scorespeaker.ui.ShotLogRowUi
import se.linusborjesson.scorespeaker.ui.Strings
import se.linusborjesson.scorespeaker.ui.TargetShot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns derived [Session]s into the platform-neutral UI states the shared
 * History / Session-detail screens render. This is where dates get formatted
 * (kept out of shared so commonMain needs no datetime dependency) and where
 * label templates from [Strings] are filled in.
 */
class HistoryFormatter(private val strings: Strings) {

    private val dayFmt get() = SimpleDateFormat("EEE", Locale.getDefault())
    private val dateFmt get() = SimpleDateFormat("d", Locale.getDefault())
    private val longDateFmt get() = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val timeFmt get() = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun build(sessions: List<Session>, now: Long): HistoryUiState {
        if (sessions.isEmpty()) return HistoryUiState(summary = null, sessions = emptyList())
        val stats = summarize(sessions)
        val recentAvg = averageSince(sessions, now - THIRTY_DAYS) ?: stats.overallAverage
        val summary = HistorySummaryUi(
            averageLabel = recentAvg?.let { "%.2f".format(it) } ?: "—",
            trend = sessions.takeLast(8).mapNotNull { it.averageScore?.toFloat() },
            sessionCount = stats.sessionCount,
            totalShots = stats.totalShots,
            bestAverageLabel = stats.bestSessionAverage?.let { "%.2f".format(it) } ?: "—",
        )
        val rows = sessions.sortedByDescending { it.startedAt }.map(::row)
        return HistoryUiState(summary = summary, sessions = rows)
    }

    fun detail(s: Session): SessionDetailUiState {
        val durationMin = ((s.endedAt - s.startedAt) / 60_000).toInt()
        val subtitle = "${longDateFmt.format(Date(s.startedAt))} · ${timeFmt.format(Date(s.startedAt))} · " +
            strings.minutesTemplate.format(durationMin)
        val missing = s.missingShotNumbers
        return SessionDetailUiState(
            title = strings.sessionTitleTemplate.format(s.index),
            subtitle = subtitle,
            totalLabel = "%.1f".format(s.totalScore),
            averageLabel = s.averageScore?.let { "%.2f".format(it) } ?: "—",
            bestLabel = s.bestScore?.let { "%.1f".format(it) } ?: "—",
            shotCount = s.shotCount,
            plotShots = s.shots.mapNotNull { it.toTargetShotOrNull() },
            log = s.shots.sortedByDescending { it.shotNumber }.map { shot ->
                ShotLogRowUi(
                    shotNumber = shot.shotNumber,
                    scoreLabel = shot.score?.let { "%.1f".format(it) } ?: "—",
                    score = shot.score,
                    note = offsetNote(shot.offsetXRings, shot.offsetYRings),
                )
            },
            missingNote = if (missing.isEmpty()) null else
                strings.missedListTemplate.format(missing.size, missing.joinToString(", ")),
        )
    }

    private fun row(s: Session) = SessionRowUi(
        index = s.index,
        dayLabel = dayFmt.format(Date(s.startedAt)).uppercase(),
        dateLabel = dateFmt.format(Date(s.startedAt)),
        shotCount = s.shotCount,
        averageLabel = strings.avgTemplate.format(s.averageScore?.let { "%.2f".format(it) } ?: "—"),
        gapNote = s.missingShotNumbers.size.takeIf { it > 0 }?.let { strings.missedTemplate.format(it) },
        trend = s.shots.mapNotNull { it.score?.toFloat() },
        totalLabel = "%.1f".format(s.totalScore),
    )

    private fun HistoryShot.toTargetShotOrNull(): TargetShot? {
        val x = offsetXRings ?: return null
        val y = offsetYRings ?: return null
        return TargetShot(
            (x / 10).coerceIn(-1.0, 1.0).toFloat(),
            (-y / 10).coerceIn(-1.0, 1.0).toFloat(),
            score ?: 0.0,
        )
    }

    /** "Low 0.4" / "Low-right 1.2" / "Centre" / "—" from green-dot offsets. */
    private fun offsetNote(x: Double?, y: Double?): String {
        if (x == null || y == null) return "—"
        val ax = abs(x); val ay = abs(y)
        if (max(ax, ay) < 0.1) return strings.noteCentre
        val vert = if (ay >= 0.1) (if (y > 0) strings.noteLow else strings.noteHigh) else null
        val horiz = if (ax >= 0.1) (if (x > 0) strings.noteRight else strings.noteLeft) else null
        val words = listOfNotNull(vert, horiz).joinToString("-")
        return "${words.replaceFirstChar { it.uppercase() }} ${"%.1f".format(hypot(x, y))}"
    }

    private companion object {
        const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1000
    }
}
