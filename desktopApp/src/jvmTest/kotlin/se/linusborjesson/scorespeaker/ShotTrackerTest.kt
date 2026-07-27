package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Test
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.cells.TextValue
import se.linusborjesson.scorespeaker.pipeline.Reading
import se.linusborjesson.scorespeaker.pipeline.ShotTracker
import se.linusborjesson.scorespeaker.processing.ShotMeasurement
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of attribution/merge behavior run with `confirmationFrames = 1`
 * (every frame trusted) so each scenario stays one-frame-per-step; the
 * confirmation gate itself is covered by [ShotConfirmationTest].
 */
class ShotTrackerTest {

    private fun reading(shot: String? = null, mode: String? = null, score: String? = null, ts: Long = 1000): Reading =
        Reading(
            timestamp = ts,
            cells = buildMap<String, se.linusborjesson.scorespeaker.cells.CellValue?> {
                if (shot != null) put("D", ScoreShotValue(shot = shot, mode = mode, score = score ?: "0.0"))
            },
        )

    @Test
    fun `first ScoreShotValue creates a new shot record`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        val outcome = tracker.process(reading(shot = "1", mode = "P", score = "7.6"))

        assertNotNull(outcome.newShot)
        assertNull(outcome.updatedShot)
        with(outcome.newShot!!) {
            assertEquals(1, shotNumber)
            assertEquals("P", mode)
            assertEquals("7.6", score)
        }
        assertEquals(1, tracker.log.size)
    }

    @Test
    fun `same shot number with same data emits no outcome`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "1", mode = "P", score = "7.6"))
        val outcome = tracker.process(reading(shot = "1", mode = "P", score = "7.6", ts = 2000))

        assertNull(outcome.newShot)
        assertNull(outcome.updatedShot)
    }

    @Test
    fun `a measurement arriving on a later frame updates the current shot`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        val m = ShotMeasurement(score = 7.5, offsetXRings = 1.0, offsetYRings = 0.5, distanceRings = 1.1)
        tracker.process(reading(shot = "1", mode = "P", score = "7.6", ts = 1000))
        val outcome = tracker.process(
            reading(shot = "1", mode = "P", score = "7.6", ts = 1100), measurement = m,
        )

        assertNull(outcome.newShot)
        assertNotNull(outcome.updatedShot)
        assertEquals(m, outcome.updatedShot!!.measurement)
        assertEquals(1, tracker.log.size)
    }

    @Test
    fun `measurement attaches to current shot via the optional argument`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "1", mode = "P", score = "7.6"))
        val measurement = ShotMeasurement(score = 7.6, offsetXRings = -3.1, offsetYRings = -1.0, distanceRings = 3.26)
        val outcome = tracker.process(
            reading(shot = "1", mode = "P", score = "7.6", ts = 1100),
            measurement = measurement,
        )

        assertNotNull(outcome.updatedShot)
        assertEquals(measurement, outcome.updatedShot!!.measurement)
        assertEquals(measurement, tracker.current?.measurement)
    }

    @Test
    fun `shot number incrementing finalizes the previous and starts a new record`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "1", mode = "P", score = "7.6"))
        val outcome = tracker.process(reading(shot = "2", mode = "P", score = "9.1"))

        assertNotNull(outcome.newShot)
        assertEquals(2, outcome.newShot!!.shotNumber)
        assertEquals(2, tracker.log.size)
        assertEquals("7.6", tracker.log[0].score)
        assertEquals("9.1", tracker.log[1].score)
    }

    @Test
    fun `shot number going down (session reset) starts a new record`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "47", score = "10.4"))
        val outcome = tracker.process(reading(shot = "1", score = "5.2"))

        assertNotNull(outcome.newShot)
        assertEquals(1, outcome.newShot!!.shotNumber)
        // Both shots remain in the log — downstream can detect the discontinuity.
        assertEquals(listOf(47, 1), tracker.log.map { it.shotNumber })
    }

    @Test
    fun `Cell D missing means no attribution — current shot is untouched`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "1", mode = "P", score = "7.6"))
        val cellsBefore = tracker.current
        val outcome = tracker.process(Reading(2000, emptyMap()))

        assertNull(outcome.newShot)
        assertNull(outcome.updatedShot)
        assertEquals(cellsBefore, tracker.current)
    }

    @Test
    fun `non-ScoreShotValue at Cell D is ignored — no attribution possible`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "1", score = "7.6"))
        val outcome = tracker.process(
            Reading(2000, mapOf("D" to TextValue("blank"))),
        )

        assertNull(outcome.newShot)
        assertNull(outcome.updatedShot)
        assertEquals(1, tracker.current?.shotNumber)
    }

    @Test
    fun `first-good score wins — later Cell D updates within the same shot are ignored`() {
        // First-good policy: the score we recorded first is the score we keep.
        // Absorbs OCR noise where a fully-settled "7.6" briefly resolves to
        // a spurious "7.7" on a glitchy frame.
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "1", mode = "P", score = "7.6"))
        val outcome = tracker.process(reading(shot = "1", mode = "P", score = "7.7", ts = 1100))

        // No change — the existing record's score field stays at the first-good value.
        assertNull(outcome.updatedShot)
        assertEquals("7.6", tracker.current?.score)
    }

    @Test
    fun `first-good measurement wins — later non-null measurements don't overwrite`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        val firstMeasurement = ShotMeasurement(score = 7.6, offsetXRings = -3.1, offsetYRings = -1.0, distanceRings = 3.26)
        val secondMeasurement = ShotMeasurement(score = 7.5, offsetXRings = -3.3, offsetYRings = -1.2, distanceRings = 3.51)

        tracker.process(reading(shot = "1", mode = "P", score = "7.6"), measurement = firstMeasurement)
        tracker.process(reading(shot = "1", mode = "P", score = "7.6", ts = 1100), measurement = secondMeasurement)

        assertEquals(firstMeasurement, tracker.current?.measurement)
    }

    @Test
    fun `timestamps reflect first and last observation`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "1", score = "7.6", ts = 1000))
        tracker.process(
            reading(shot = "1", score = "7.6", ts = 1500),
            measurement = ShotMeasurement(score = 7.5, offsetXRings = 1.0, offsetYRings = 0.5, distanceRings = 1.1),
        )

        val record = tracker.current!!
        assertEquals(1000, record.firstSeenAt)
        assertEquals(1500, record.lastUpdatedAt)
    }

    @Test
    fun `reset clears state — next reading is a new shot 1 even after history`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "1", score = "7.6"))
        tracker.process(reading(shot = "2", score = "8.4"))
        tracker.reset()

        val outcome = tracker.process(reading(shot = "1", score = "5.2"))
        assertNotNull(outcome.newShot)
        assertEquals(1, tracker.log.size)
    }

    @Test
    fun `unparseable shot number is dropped without corrupting state`() {
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "1", score = "7.6"))
        val outcome = tracker.process(
            Reading(
                2000,
                mapOf("D" to ScoreShotValue(shot = "?", mode = null, score = "0.0")),
            ),
        )

        assertNull(outcome.newShot)
        assertNull(outcome.updatedShot)
        assertEquals(1, tracker.current?.shotNumber)
        assertTrue(tracker.log.size == 1)
    }

    @Test
    fun `log is a snapshot — later mutations don't reach an already-taken reference`() {
        // Cross-thread readers (the key-query path) hold a log reference
        // while the analyzer keeps processing; the reference must be an
        // immutable snapshot, not a view of live state.
        val tracker = ShotTracker(confirmationFrames = 1)
        tracker.process(reading(shot = "1", score = "7.6"))
        val snapshot = tracker.log

        tracker.process(reading(shot = "2", score = "9.8"))
        assertEquals(1, snapshot.size)
        assertEquals(2, tracker.log.size)

        tracker.reset()
        assertEquals(1, snapshot.size)
        assertTrue(tracker.log.isEmpty())
    }
}
