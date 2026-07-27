package se.linusborjesson.scorespeaker.pipeline

import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.processing.MissDetection
import se.linusborjesson.scorespeaker.processing.ShotMeasurement

/**
 * Everything we know about one shot. Built up frame-by-frame as data arrives
 * from the shot reader ([mode], the read [score] kept as metadata) and from
 * the green-dot scoring path ([measurement] — the product score). Any field
 * may be null if that piece hasn't been observed yet for this shot.
 */
data class ShotRecord(
    val shotNumber: Int,
    val mode: String?,
    val score: String?,
    val measurement: ShotMeasurement?,
    val firstSeenAt: Long,
    val lastUpdatedAt: Long,
    /**
     * Set when the display placed this shot off the target face. A miss
     * and a [measurement] are mutually exclusive: there is no marker on
     * the face to measure, and the badge's rim position is a direction,
     * not a ring offset. Kept separate from [measurement] precisely so a
     * miss can never leak a fabricated offset into the group average.
     */
    val miss: MissDetection? = null,
)

/**
 * What [ShotTracker.process] did this tick.
 *
 * @param newShot Non-null when a shot-number transition was *confirmed* this
 *   tick — i.e. a new shot started. The record represents the just-started
 *   shot, *not* the previous one (the previous one is the last entry of
 *   [ShotTracker.log] before this one was appended).
 * @param updatedShot Non-null when an existing shot's record gained or
 *   changed a field. The two are mutually exclusive: a new shot is always
 *   reported as [newShot] even if it also has data.
 */
data class ShotProcessOutcome(
    val newShot: ShotRecord? = null,
    val updatedShot: ShotRecord? = null,
)

/**
 * Per-shot state machine. Watches Cell D for `ScoreShotValue`s to decide
 * when one shot ends and the next begins; collects the green-dot
 * [ShotMeasurement] into the current shot's [ShotRecord].
 *
 * State transitions are driven by Cell D's `shot` field:
 *  - a shot number different from the current shot's (or the first one
 *    ever seen) makes that number a *candidate*. Only after
 *    [confirmationFrames] consecutive attributable frames agree on the
 *    number does the shot actually start — a single misread ("1"
 *    resolving to "4" on one glitchy frame) must not produce a spoken
 *    announcement and a database row, which is exactly what an
 *    unconditional transition did. Candidate frames still collect data
 *    first-good, so nothing observed while confirming is lost, and
 *    `firstSeenAt` is the first candidate frame's time.
 *  - a frame agreeing with the *current* shot number discards any
 *    candidate: the deviation was noise.
 *  - shot number unchanged → fill in any field still null on the current
 *    record; first non-null value wins. Once a field is set for a shot,
 *    later frames don't overwrite — absorbs transient read / dot-detection
 *    noise without changing the recorded value.
 *
 * Cell D becoming null is *not* a transition — the [ChangeTracker]
 * upstream already holds last-known-good across transient read misses, so
 * a null here means "we genuinely have no shot number to attribute data
 * to". In that case we leave the current shot *and* any candidate
 * untouched: a detection gap in the middle of confirming a real new shot
 * shouldn't restart the count. Subsequent non-null measurements arriving
 * when no shot is current are dropped (we can't attribute them).
 *
 * Mutations are not thread-safe — drive [process]/[reset] from a single
 * coroutine/thread (on Android: the camera analyzer thread). Reading
 * [log]/[current] is safe from any thread: the log is an immutable list
 * behind a volatile reference, swapped whole on each shot event, so a
 * reader always sees a consistent snapshot (at worst one event stale).
 */
class ShotTracker(
    /**
     * Consecutive attributable frames that must agree on a new shot number
     * before the shot starts. 1 = trust every frame (the pre-gate
     * behavior; useful in tests that aren't about confirmation).
     */
    private val confirmationFrames: Int = DEFAULT_CONFIRMATION_FRAMES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /**
         * Default gate depth. At the Android analyzer's throughput this is
         * well under a second of latency, and two frames repeating the same
         * misread are already far less likely than one.
         */
        const val DEFAULT_CONFIRMATION_FRAMES = 3
    }

    /** A shot number seen but not yet confirmed, with data collected so far. */
    private class Candidate(val shotNumber: Int, val firstSeenAt: Long) {
        var mode: String? = null
        var score: String? = null
        var measurement: ShotMeasurement? = null
        var miss: MissDetection? = null
        var framesSeen: Int = 0
    }

    // Immutable list swapped whole on each mutation — shot events are rare
    // (~one per 30 s, n ≤ a series) so the copies are free, and any-thread
    // reads are memory-model-clean without locks.
    @Volatile
    private var records: List<ShotRecord> = emptyList()
    private var candidate: Candidate? = null

    /** All shots seen so far, in observation order. Last entry is [current]. */
    val log: List<ShotRecord> get() = records

    /** The shot in progress (last entry of [log]), or null if none yet. */
    val current: ShotRecord? get() = records.lastOrNull()

    /**
     * Update internal state from one [reading] and an optional
     * [measurement] from the green-dot path. Returns what happened this
     * tick — see [ShotProcessOutcome].
     */
    fun process(
        reading: Reading,
        measurement: ShotMeasurement? = null,
        miss: MissDetection? = null,
    ): ShotProcessOutcome {
        val scoreShot = reading.cells["D"] as? ScoreShotValue
        val now = reading.timestamp.takeIf { it > 0 } ?: clock()

        // No shot number this tick — nothing to attribute. (Holds upstream
        // should make this rare.)
        if (scoreShot == null) return ShotProcessOutcome()

        val shotNumber = scoreShot.shot.toIntOrNull()
            ?: return ShotProcessOutcome() // unparseable — drop rather than corrupt state

        val curr = current
        return if (curr != null && curr.shotNumber == shotNumber) {
            // Current shot re-confirmed — any pending candidate was noise.
            candidate = null
            // First-good policy: once a field is set for a shot, later
            // frames don't overwrite it. Only missing fields get filled
            // in. This absorbs read / dot-detection noise within a shot
            // without flipping the recorded value back and forth, at the
            // cost of ignoring genuine SIUS re-resolutions of a score
            // (acceptable — re-resolutions are rare and the first-good
            // value is typically right).
            val merged = curr.copy(
                measurement = curr.measurement ?: measurement,
                miss = curr.miss ?: miss,
                lastUpdatedAt = now,
            )
            // Same fields, only timestamp moved? No real update.
            if (merged.copy(lastUpdatedAt = curr.lastUpdatedAt) == curr) {
                ShotProcessOutcome()
            } else {
                records = records.dropLast(1) + merged
                ShotProcessOutcome(updatedShot = merged)
            }
        } else {
            confirmCandidate(shotNumber, scoreShot, measurement, miss, now)
        }
    }

    private fun confirmCandidate(
        shotNumber: Int,
        scoreShot: ScoreShotValue,
        measurement: ShotMeasurement?,
        miss: MissDetection?,
        now: Long,
    ): ShotProcessOutcome {
        val cand = candidate?.takeIf { it.shotNumber == shotNumber }
            ?: Candidate(shotNumber, firstSeenAt = now).also { candidate = it }
        cand.framesSeen++
        if (cand.mode == null) cand.mode = scoreShot.mode
        if (cand.score == null) cand.score = scoreShot.score
        if (cand.measurement == null) cand.measurement = measurement
        if (cand.miss == null) cand.miss = miss

        if (cand.framesSeen < confirmationFrames) return ShotProcessOutcome()

        candidate = null
        val record = ShotRecord(
            shotNumber = cand.shotNumber,
            mode = cand.mode,
            score = cand.score,
            measurement = cand.measurement,
            miss = cand.miss,
            firstSeenAt = cand.firstSeenAt,
            lastUpdatedAt = now,
        )
        records = records + record
        return ShotProcessOutcome(newShot = record)
    }

    fun reset() {
        records = emptyList()
        candidate = null
    }
}
