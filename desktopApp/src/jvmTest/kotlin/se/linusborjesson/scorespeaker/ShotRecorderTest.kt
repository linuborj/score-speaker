package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Test
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.db.DriverFactory
import se.linusborjesson.scorespeaker.db.createDatabase
import se.linusborjesson.scorespeaker.pipeline.Reading
import se.linusborjesson.scorespeaker.pipeline.ShotRecorder
import se.linusborjesson.scorespeaker.pipeline.ShotTracker
import se.linusborjesson.scorespeaker.processing.ShotMeasurement
import kotlin.test.assertEquals

class ShotRecorderTest {

    private fun newRecorder(): Pair<ShotRecorder, se.linusborjesson.scorespeaker.db.ScoreSpeakerDb> {
        val db = createDatabase(DriverFactory())  // in-memory
        return ShotRecorder(db) to db
    }

    private fun reading(shot: String, mode: String? = null, score: String = "0.0", ts: Long = 1000) =
        Reading(
            timestamp = ts,
            cells = mapOf<String, se.linusborjesson.scorespeaker.cells.CellValue?>(
                "D" to ScoreShotValue(shot = shot, mode = mode, score = score),
            ),
        )

    @Test
    fun `newShot inserts one row`() {
        val (recorder, db) = newRecorder()
        val tracker = ShotTracker(confirmationFrames = 1)

        recorder.record(tracker.process(reading(shot = "1", mode = "P", score = "7.6")))

        val rows = db.shotQueries.selectAll().executeAsList()
        assertEquals(1, rows.size)
        with(rows[0]) {
            assertEquals(1, shot_number.toInt())
            assertEquals("P", mode)
            assertEquals("7.6", score)
        }
    }

    @Test
    fun `updatedShot updates the same row — measurement arrives a frame later`() {
        val (recorder, db) = newRecorder()
        val tracker = ShotTracker(confirmationFrames = 1)

        recorder.record(tracker.process(reading(shot = "1", mode = "P", score = "7.6", ts = 1000)))
        val measurement = ShotMeasurement(7.6, offsetXRings = -3.1, offsetYRings = 1.0, distanceRings = 3.26)
        recorder.record(
            tracker.process(reading(shot = "1", mode = "P", score = "7.6", ts = 1100), measurement),
        )

        val rows = db.shotQueries.selectAll().executeAsList()
        assertEquals(1, rows.size, "second tick within the same shot must not insert a new row")
        with(rows[0]) {
            assertEquals(-3.1, measurement_offset_x_rings)
            assertEquals(1.0, measurement_offset_y_rings)
            assertEquals(1100, last_updated_at)
        }
    }

    @Test
    fun `new shot number inserts a second row`() {
        val (recorder, db) = newRecorder()
        val tracker = ShotTracker(confirmationFrames = 1)

        recorder.record(tracker.process(reading(shot = "1", mode = "P", score = "7.6", ts = 1000)))
        recorder.record(tracker.process(reading(shot = "2", mode = "P", score = "9.1", ts = 2000)))

        val rows = db.shotQueries.selectAll().executeAsList()
        assertEquals(2, rows.size)
        assertEquals(listOf(1L, 2L), rows.map { it.shot_number })
        assertEquals(listOf("7.6", "9.1"), rows.map { it.score })
    }

    @Test
    fun `session reset (shot number going down) inserts a new row, prior session preserved`() {
        val (recorder, db) = newRecorder()
        val tracker = ShotTracker(confirmationFrames = 1)

        recorder.record(tracker.process(reading(shot = "47", score = "10.4", ts = 1000)))
        recorder.record(tracker.process(reading(shot = "1", score = "5.2", ts = 2000)))

        val rows = db.shotQueries.selectAll().executeAsList()
        assertEquals(2, rows.size)
        assertEquals(listOf(47L, 1L), rows.map { it.shot_number })
    }

    @Test
    fun `legacy coords columns are written NULL`() {
        // Nothing reads Cell G; the columns survive until the
        // schema's first real migration, always NULL.
        val (recorder, db) = newRecorder()
        val tracker = ShotTracker(confirmationFrames = 1)

        recorder.record(tracker.process(reading(shot = "1", mode = "P", score = "7.6", ts = 1000)))

        with(db.shotQueries.selectLatest().executeAsOne()) {
            assertEquals(null, coords_x)
            assertEquals(null, coords_y)
            assertEquals(null, coords_x_direction)
            assertEquals(null, coords_y_direction)
        }
    }

    @Test
    fun `null outcome (no new or updated shot) writes nothing`() {
        val (recorder, db) = newRecorder()
        val tracker = ShotTracker(confirmationFrames = 1)

        recorder.record(tracker.process(reading(shot = "1", score = "7.6", ts = 1000)))
        val before = db.shotQueries.selectAll().executeAsList()
        // Same data again → no outcome
        recorder.record(tracker.process(reading(shot = "1", score = "7.6", ts = 1100)))
        val after = db.shotQueries.selectAll().executeAsList()
        assertEquals(before, after)
    }

    @Test
    fun `gap detection — selectAll returns shots in observation order so callers can scan for discontinuities`() {
        val (recorder, db) = newRecorder()
        val tracker = ShotTracker(confirmationFrames = 1)

        // Shots 1, 2, 5 (3 and 4 missed during a look-away)
        recorder.record(tracker.process(reading(shot = "1", score = "7.6", ts = 1000)))
        recorder.record(tracker.process(reading(shot = "2", score = "8.1", ts = 2000)))
        recorder.record(tracker.process(reading(shot = "5", score = "9.4", ts = 3000)))

        val rows = db.shotQueries.selectAll().executeAsList()
        assertEquals(listOf(1L, 2L, 5L), rows.map { it.shot_number })
        // Gap detection is a query-side concern; the recorder just preserves order.
    }
}
