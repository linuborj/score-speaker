package se.linusborjesson.scorespeaker.pipeline

import se.linusborjesson.scorespeaker.db.ScoreSpeakerDb
import se.linusborjesson.scorespeaker.db.Shot

/**
 * One shot as stored, reduced to what history/stats need. [score] is parsed to
 * a number (null if it never OCR'd cleanly); the green-dot [offsetXRings] /
 * [offsetYRings] position the shot on a group plot.
 */
data class HistoryShot(
    val shotNumber: Int,
    val score: Double?,
    val offsetXRings: Double?,
    val offsetYRings: Double?,
    val observedAt: Long,
)

/**
 * A derived shooting session — a contiguous run of shots. Sessions are never
 * stored; they're computed from the shot stream (see [deriveSessions]), so the
 * boundary policy lives here, not in the schema.
 */
data class Session(
    val index: Int,                 // 1-based, chronological
    val shots: List<HistoryShot>,
) {
    val startedAt: Long get() = shots.first().observedAt
    val endedAt: Long get() = shots.last().observedAt
    val shotCount: Int get() = shots.size

    private val scored: List<Double> get() = shots.mapNotNull { it.score }
    val totalScore: Double get() = scored.sum()
    val averageScore: Double? get() = scored.takeIf { it.isNotEmpty() }?.average()
    val bestScore: Double? get() = scored.maxOrNull()

    /**
     * Shot numbers expected within this session (its min..max) but never
     * observed — look-aways, screen swaps, or an OCR run of bad frames.
     * Honest reporting depends on surfacing these rather than silently
     * dropping them from totals.
     */
    val missingShotNumbers: List<Int> get() {
        if (shots.isEmpty()) return emptyList()
        val present = shots.map { it.shotNumber }.toSet()
        return (present.min()..present.max()).filter { it !in present }
    }
}

/** Default inactivity gap that splits sessions: 30 minutes of no new shot. */
const val DEFAULT_SESSION_GAP_MILLIS: Long = 30 * 60 * 1000

/**
 * Split a flat shot stream into sessions. A new session starts when, relative
 * to the previous shot:
 *  - the **shot number does not advance** (`<=` previous) — a new match began
 *    and the SIUS counter reset, or the same shot was re-observed after a
 *    reset; or
 *  - the **time gap exceeds [gapMillis]** — the shooter stepped away.
 *
 * Input order doesn't matter; shots are sorted by [HistoryShot.observedAt].
 */
fun deriveSessions(
    shots: List<HistoryShot>,
    gapMillis: Long = DEFAULT_SESSION_GAP_MILLIS,
): List<Session> {
    if (shots.isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<HistoryShot>>()
    for (shot in shots.sortedBy { it.observedAt }) {
        val current = groups.lastOrNull()
        val prev = current?.last()
        val boundary = prev != null &&
            (shot.shotNumber <= prev.shotNumber || shot.observedAt - prev.observedAt > gapMillis)
        if (current == null || boundary) groups.add(mutableListOf(shot)) else current.add(shot)
    }
    return groups.mapIndexed { i, g -> Session(i + 1, g) }
}

/** Cross-session aggregates for the history summary. */
data class HistoryStats(
    val sessionCount: Int,
    val totalShots: Int,
    val overallAverage: Double?,
    val bestSessionAverage: Double?,
)

fun summarize(sessions: List<Session>): HistoryStats {
    val allScored = sessions.flatMap { it.shots }.mapNotNull { it.score }
    return HistoryStats(
        sessionCount = sessions.size,
        totalShots = sessions.sumOf { it.shotCount },
        overallAverage = allScored.takeIf { it.isNotEmpty() }?.average(),
        bestSessionAverage = sessions.mapNotNull { it.averageScore }.maxOrNull(),
    )
}

/** Average shot score across sessions whose last shot lands on/after [sinceMillis]. */
fun averageSince(sessions: List<Session>, sinceMillis: Long): Double? =
    sessions.filter { it.endedAt >= sinceMillis }
        .flatMap { it.shots }
        .mapNotNull { it.score }
        .takeIf { it.isNotEmpty() }
        ?.average()

/**
 * Read-side over the [ScoreSpeakerDb] shot table. The recorder
 * ([ShotRecorder]) writes; this reads and derives sessions on demand.
 */
class ShotHistory(private val db: ScoreSpeakerDb) {
    fun shots(): List<HistoryShot> =
        db.shotQueries.selectAll().executeAsList().map { it.toHistoryShot() }

    fun sessions(gapMillis: Long = DEFAULT_SESSION_GAP_MILLIS): List<Session> =
        deriveSessions(shots(), gapMillis)
}

private fun Shot.toHistoryShot() = HistoryShot(
    shotNumber = shot_number.toInt(),
    score = score?.toDoubleOrNull(),
    offsetXRings = measurement_offset_x_rings,
    offsetYRings = measurement_offset_y_rings,
    observedAt = first_seen_at,
)
