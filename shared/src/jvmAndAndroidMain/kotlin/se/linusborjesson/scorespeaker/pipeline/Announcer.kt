package se.linusborjesson.scorespeaker.pipeline

import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.cells.TextValue
import se.linusborjesson.scorespeaker.processing.MissDetection
import se.linusborjesson.scorespeaker.processing.ShotMeasurement
import se.linusborjesson.scorespeaker.settings.OffsetStyle

/**
 * Something the app wants to say out loud. The actual output (TTS, log,
 * on-screen overlay) is handled by an [AnnouncerSink] — this type is just
 * the policy decision: *what* to say, *how important*.
 */
data class Announcement(
    val text: String,
    val priority: Priority,
    val timestamp: Long,
    val source: AnnouncementSource,
)

/** Where an [Announcement] came from. Lets tests / sinks distinguish kinds. */
sealed class AnnouncementSource {
    /** Triggered by a per-cell semantic change (Cell D, F, G, …). */
    data class FromCellChange(val change: Change) : AnnouncementSource() {
        val cellName: String get() = change.cellName
    }
    /** Triggered by a ShotTracker outcome — e.g. green-dot offset for a shot. */
    data class FromShot(
        val shotNumber: Int,
        val kind: ShotAnnouncementKind,
    ) : AnnouncementSource()
    /** Triggered by an explicit user query (bound hardware key). */
    data object FromQuery : AnnouncementSource()
}

enum class ShotAnnouncementKind { SCORE, MEASUREMENT }

/** Used by sinks to prioritise or drop announcements under load. */
enum class Priority { LOW, MEDIUM, HIGH }

/** Where announcements go. Android plugs in TTS here; desktop logs. */
fun interface AnnouncerSink {
    fun announce(announcement: Announcement)
}

/**
 * Translates [Change] events into [Announcement]s via [policy] and forwards
 * them to [sink]. Pure JVM — testable without a phone.
 *
 * Not thread-safe: drive from a single coroutine / event loop.
 */
class Announcer(
    private val sink: AnnouncerSink,
    private val policy: AnnouncerPolicy = AnnouncerPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Per-shot dedup: don't re-announce the same score or measurement on
    // later updatedShot ticks (e.g. when coordinates arrive later but the
    // score/measurement are unchanged), and don't re-announce a shot's
    // score on re-acquisition (ShotTracker doesn't see a transition, so
    // `newShot` won't fire — this set is the belt to that suspenders).
    private val scoreAnnouncedFor = mutableSetOf<Int>()
    private val lastMeasurementAnnounced = mutableMapOf<Int, ShotMeasurement>()
    private val missAnnouncedFor = mutableSetOf<Int>()
    private var lastShotNumber = 0

    fun onChanges(changes: List<Change>) {
        for (change in changes) {
            val text = policy.format(change) ?: continue
            sink.announce(
                Announcement(
                    text = text,
                    priority = policy.priority(change),
                    timestamp = clock(),
                    source = AnnouncementSource.FromCellChange(change),
                ),
            )
        }
    }

    /**
     * Announce a ShotTracker outcome. On `newShot` we emit the score at
     * HIGH priority; whenever a measurement is present (on this tick or a
     * later updatedShot) we emit the offset at MEDIUM priority so it
     * queues behind the score (`QUEUE_ADD` in the TTS sink) and never
     * cuts the score off.
     *
     * Per-shot dedup means a re-acquisition of the same shot after a
     * detection gap stays silent — ShotTracker only fires `newShot` on
     * actual shot-number transitions, and even if it did, the score
     * dedup would suppress.
     */
    fun onShotOutcome(outcome: ShotProcessOutcome) {
        outcome.newShot?.let { shot ->
            // A shot number going DOWN means a new series started (the SIUS
            // counter reset). Shot numbers repeat across series, so the
            // per-shot dedup must restart — otherwise series two's "shot 1"
            // score is silently suppressed while its (different-valued)
            // measurement still speaks. Field symptom: hearing only
            // "1 o'clock" with no score before it.
            if (shot.shotNumber < lastShotNumber) reset()
            lastShotNumber = shot.shotNumber
            announceScoreIfNew(shot)
        }
        // Either branch may carry a measurement — newShot can already have
        // one when the green-dot pipeline produces a result on the boundary
        // frame; updatedShot carries one when it arrived a frame or two
        // later.
        val shot = outcome.newShot ?: outcome.updatedShot ?: return
        announceMeasurementIfNew(shot)
    }

    private fun announceScoreIfNew(shot: ShotRecord) {
        if (shot.shotNumber in scoreAnnouncedFor) return
        val text = policy.formatScore(shot) ?: return
        sink.announce(
            Announcement(
                text = text,
                priority = Priority.HIGH,
                timestamp = clock(),
                source = AnnouncementSource.FromShot(shot.shotNumber, ShotAnnouncementKind.SCORE),
            ),
        )
        scoreAnnouncedFor += shot.shotNumber
    }

    private fun announceMeasurementIfNew(shot: ShotRecord) {
        // A miss carries a direction instead of a measurement; the two are
        // mutually exclusive on a record.
        shot.miss?.let { miss ->
            if (missAnnouncedFor.add(shot.shotNumber)) {
                policy.formatMiss(miss)?.let { text ->
                    sink.announce(
                        Announcement(
                            text = text,
                            priority = Priority.MEDIUM,
                            timestamp = clock(),
                            source = AnnouncementSource.FromShot(
                                shot.shotNumber, ShotAnnouncementKind.MEASUREMENT,
                            ),
                        ),
                    )
                }
            }
            return
        }
        val measurement = shot.measurement ?: return
        if (lastMeasurementAnnounced[shot.shotNumber] == measurement) return
        val text = policy.formatMeasurement(measurement) ?: return
        sink.announce(
            Announcement(
                text = text,
                priority = Priority.MEDIUM,
                timestamp = clock(),
                source = AnnouncementSource.FromShot(shot.shotNumber, ShotAnnouncementKind.MEASUREMENT),
            ),
        )
        lastMeasurementAnnounced[shot.shotNumber] = measurement
    }

    /**
     * Clear per-shot state. Called automatically when a shot number goes
     * backwards (new series); external calls remain for full session resets.
     */
    fun reset() {
        scoreAnnouncedFor.clear()
        lastMeasurementAnnounced.clear()
        missAnnouncedFor.clear()
        lastShotNumber = 0
    }
}

/**
 * Decides what to say for a given change — language, verbosity, which
 * cells matter, etc. are all knobs the visually-impaired-shooter end user
 * might want to tune. Format methods return null to suppress.
 *
 * - **Score** — announced once per shot via [formatScore] driven by
 *   `Announcer.onShotOutcome`. The Cell-D change path is silent because
 *   `ChangeTracker` can spuriously re-fire `null → "1P 7.6"` on
 *   re-acquisition after a detection gap; the ShotTracker-driven path
 *   is the authoritative trigger.
 * - **Status (F)** — speak the text ("KLAR", "STOPP") as-is.
 * - everything else — silent.
 *
 * Decimals are written out with [SpeechStrings.decimalWord] ("point" /
 * "komma") so the TTS engine reads "7.6" as words rather than parsing the
 * dot inconsistently. All wording comes from [speech]; pass
 * [SwedishSpeech] (or `speechStringsFor(locale)`) for a localized voice.
 */
class AnnouncerPolicy(
    // Behavioral knobs are `var` so a settings change can mutate the live
    // policy instance without rebuilding the Announcer (which would drop its
    // per-shot dedup state).
    var includeShotNumber: Boolean = true,
    var includeMode: Boolean = true,
    var announceScore: Boolean = true,
    var announceMeasurement: Boolean = true,
    /** Mutable so a language-setting change swaps wording in place. */
    var speech: SpeechStrings = EnglishSpeech,
    /** Spoken error format — direction words or clock position. */
    var offsetStyle: OffsetStyle = OffsetStyle.DIRECTIONS,
    /** Decimals in spoken offsets/scores ("left 3.1") or whole ("left 3"). */
    var offsetDecimals: Boolean = true,
) {

    fun format(change: Change): String? {
        // Don't announce a transition TO blank (that's just "shot ended" noise).
        if (change.to == null) return null
        return when (change.cellName) {
            // Cell D is announced via the ShotTracker path, not here —
            // re-acquisition after a detection gap can spuriously emit a
            // Cell D change with the same shot. See [formatScore].
            "F" -> formatStatus(change.to)
            else -> null
        }
    }

    fun priority(change: Change): Priority = when (change.cellName) {
        "F" -> Priority.MEDIUM
        else -> Priority.LOW
    }

    fun formatScore(shot: ShotRecord): String? {
        if (!announceScore) return null
        // A miss has no score to speak — say so instead of "0 point 0",
        // which sounds like a measured result rather than an off-target
        // shot. The direction rides on the measurement utterance.
        val score = if (shot.miss != null) speech.miss else shot.score?.let { pronounceDecimal(it) }
        if (score == null) return null
        val parts = mutableListOf<String>()
        if (includeShotNumber && shot.shotNumber > 0) parts += "${speech.shot} ${shot.shotNumber}"
        if (includeMode && shot.mode == "P") parts += speech.practice
        parts += score
        return parts.joinToString(", ")
    }

    /**
     * Which way the shot left the target, from the badge's rim position.
     * Always clock-style regardless of [offsetStyle]: the badge gives a
     * bearing, not a per-axis magnitude, so "left 0.9, up 0.4" would dress
     * a direction up as a measurement. Null when misses aren't announced.
     */
    fun formatMiss(miss: MissDetection): String? {
        if (!announceMeasurement) return null
        return OffsetSpeech.phrase(
            miss.dx, miss.dy, speech,
            style = OffsetStyle.CLOCK, withDecimal = false,
        )
    }

    private fun formatStatus(value: CellValue): String? = when (value) {
        is TextValue -> value.text
        else -> null
    }

    /**
     * Format the green-dot offset per [offsetStyle]/[offsetDecimals] —
     * "right 3 point 1, down 1 point 0" or "3 o'clock" (see
     * [OffsetSpeech]). Returns null when the dot is centred — for a
     * dead-centre 10.9, "0 point 0, 0 point 0" would be noise.
     */
    fun formatMeasurement(measurement: ShotMeasurement): String? {
        if (!announceMeasurement) return null
        return OffsetSpeech.phrase(
            measurement.offsetXRings,
            measurement.offsetYRings,
            speech = speech,
            style = offsetStyle,
            withDecimal = offsetDecimals,
        )
    }

    /** "7.6" → "7 point 6", "10.4" → "10 point 4", "7" → "7". */
    private fun pronounceDecimal(s: String): String =
        s.replace(".", " ${speech.decimalWord} ").replace(",", " ${speech.decimalWord} ")

}

/**
 * Captures every announcement in a list — useful for tests. Not thread-safe.
 */
class RecordingAnnouncerSink : AnnouncerSink {
    private val _list = mutableListOf<Announcement>()
    val list: List<Announcement> get() = _list
    fun clear() = _list.clear()
    override fun announce(announcement: Announcement) {
        _list += announcement
    }
}
