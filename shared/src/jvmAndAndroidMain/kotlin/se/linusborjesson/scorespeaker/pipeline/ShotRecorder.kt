package se.linusborjesson.scorespeaker.pipeline

import se.linusborjesson.scorespeaker.db.ScoreSpeakerDb

/**
 * Writes [ShotProcessOutcome]s into the [ScoreSpeakerDb].
 *
 * - On `newShot` → INSERT a row, remember its rowid as the "current" row.
 * - On `updatedShot` → UPDATE that row with the latest fields.
 *
 * Per the first-good policy upstream in [ShotTracker], fields don't flip
 * back and forth within a shot, so each UPDATE just fills in newly-arrived
 * data (the measurement) without re-writing settled fields.
 *
 * The `coords_*` columns are unused (nothing reads Cell G) and are written
 * NULL; drop them whenever the schema first needs a real migration.
 *
 * Not thread-safe — call from the same thread that drives [ShotTracker]
 * (on Android: the camera analyzer thread, keeping SQLite writes off the
 * main thread; the Android SQLite driver serializes them against
 * main-thread history reads).
 */
class ShotRecorder(db: ScoreSpeakerDb) {

    private val queries = db.shotQueries
    private var currentRowId: Long? = null

    fun record(outcome: ShotProcessOutcome) {
        outcome.newShot?.let(::insert)
        outcome.updatedShot?.let(::updateCurrent)
    }

    private fun insert(shot: ShotRecord) {
        queries.insertShot(
            shot_number = shot.shotNumber.toLong(),
            mode = shot.mode,
            score = shot.score,
            coords_x = null,
            coords_y = null,
            coords_x_direction = null,
            coords_y_direction = null,
            measurement_score = shot.measurement?.score,
            measurement_offset_x_rings = shot.measurement?.offsetXRings,
            measurement_offset_y_rings = shot.measurement?.offsetYRings,
            measurement_distance_rings = shot.measurement?.distanceRings,
            first_seen_at = shot.firstSeenAt,
            last_updated_at = shot.lastUpdatedAt,
        )
        currentRowId = queries.lastInsertedId().executeAsOne()
    }

    private fun updateCurrent(shot: ShotRecord) {
        val id = currentRowId ?: return
        queries.updateShotById(
            score = shot.score,
            coords_x = null,
            coords_y = null,
            coords_x_direction = null,
            coords_y_direction = null,
            measurement_score = shot.measurement?.score,
            measurement_offset_x_rings = shot.measurement?.offsetXRings,
            measurement_offset_y_rings = shot.measurement?.offsetYRings,
            measurement_distance_rings = shot.measurement?.distanceRings,
            last_updated_at = shot.lastUpdatedAt,
            id = id,
        )
    }
}

