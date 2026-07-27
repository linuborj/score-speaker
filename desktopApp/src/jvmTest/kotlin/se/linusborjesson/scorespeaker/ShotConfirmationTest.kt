package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Test
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.pipeline.Reading
import se.linusborjesson.scorespeaker.pipeline.ShotTracker
import se.linusborjesson.scorespeaker.processing.ShotMeasurement
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The confirmation gate: a new shot number must be seen on
 * `confirmationFrames` consecutive attributable frames before a shot
 * starts. This is what keeps a single misread from becoming a spoken
 * phantom shot and a phantom DB row.
 */
class ShotConfirmationTest {

    private fun reading(shot: String, score: String = "7.6", ts: Long = 1000): Reading =
        Reading(
            timestamp = ts,
            cells = mapOf<String, se.linusborjesson.scorespeaker.cells.CellValue?>(
                "D" to ScoreShotValue(shot = shot, mode = "P", score = score),
            ),
        )

    /** Feed [shot] until confirmed; returns the tick count it took. */
    private fun ShotTracker.confirm(shot: String, ts: Long = 1000): Int {
        for (i in 1..10) {
            if (process(reading(shot, ts = ts + i)).newShot != null) return i
        }
        error("shot $shot never confirmed in 10 frames")
    }

    @Test
    fun `a new shot starts only after the default three consistent frames`() {
        val tracker = ShotTracker()
        assertNull(tracker.process(reading("1", ts = 1000)).newShot)
        assertNull(tracker.process(reading("1", ts = 1100)).newShot)
        val third = tracker.process(reading("1", ts = 1200))
        assertNotNull(third.newShot)
        assertEquals(1, third.newShot!!.shotNumber)
        assertEquals(1, tracker.log.size)
    }

    @Test
    fun `a single misread frame does not create a phantom shot`() {
        val tracker = ShotTracker()
        tracker.confirm("1")
        // One glitchy frame resolves the shot number to 4 …
        assertNull(tracker.process(reading("4", ts = 2000)).newShot)
        // … then the display reads correctly again: candidate discarded.
        tracker.process(reading("1", ts = 2100))
        // Even two more (non-consecutive) misreads never accumulate.
        assertNull(tracker.process(reading("4", ts = 2200)).newShot)
        tracker.process(reading("1", ts = 2300))
        assertNull(tracker.process(reading("4", ts = 2400)).newShot)
        tracker.process(reading("1", ts = 2500))

        assertEquals(listOf(1), tracker.log.map { it.shotNumber })
    }

    @Test
    fun `data seen while confirming is kept — measurement, firstSeenAt`() {
        val tracker = ShotTracker()
        tracker.confirm("1")

        val measurement = ShotMeasurement(score = 9.1, offsetXRings = 0.5, offsetYRings = -0.3, distanceRings = 0.58)
        assertNull(tracker.process(reading("2", ts = 5000)).newShot)
        assertNull(tracker.process(reading("2", ts = 5100), measurement = measurement).newShot)
        val outcome = tracker.process(reading("2", ts = 5200))

        with(outcome.newShot!!) {
            assertEquals(2, shotNumber)
            assertEquals(measurement, this.measurement)
            assertEquals(5000, firstSeenAt) // first candidate frame, not confirmation frame
            assertEquals(5200, lastUpdatedAt)
        }
    }

    @Test
    fun `an unattributable frame mid-confirmation does not reset the count`() {
        val tracker = ShotTracker()
        assertNull(tracker.process(reading("1", ts = 1000)).newShot)
        // Detection gap: no Cell D this tick.
        tracker.process(Reading(1100, emptyMap()))
        assertNull(tracker.process(reading("1", ts = 1200)).newShot)
        assertNotNull(tracker.process(reading("1", ts = 1300)).newShot)
    }

    @Test
    fun `a different candidate number restarts confirmation from one`() {
        val tracker = ShotTracker()
        tracker.process(reading("1", ts = 1000))
        tracker.process(reading("1", ts = 1100))
        // Misread interrupts right before confirmation …
        tracker.process(reading("7", ts = 1200))
        // … so "1" needs a fresh three frames.
        assertNull(tracker.process(reading("1", ts = 1300)).newShot)
        assertNull(tracker.process(reading("1", ts = 1400)).newShot)
        assertNotNull(tracker.process(reading("1", ts = 1500)).newShot)
    }

    @Test
    fun `reset drops a pending candidate`() {
        val tracker = ShotTracker()
        tracker.process(reading("1", ts = 1000))
        tracker.process(reading("1", ts = 1100))
        tracker.reset()
        assertNull(tracker.process(reading("1", ts = 2000)).newShot)
        assertNull(tracker.process(reading("1", ts = 2100)).newShot)
        assertNotNull(tracker.process(reading("1", ts = 2200)).newShot)
    }

    @Test
    fun `session reset to a lower shot number is also gated`() {
        val tracker = ShotTracker()
        tracker.confirm("10")
        assertNull(tracker.process(reading("1", ts = 9000)).newShot)
        assertNull(tracker.process(reading("1", ts = 9100)).newShot)
        val outcome = tracker.process(reading("1", ts = 9200))
        assertNotNull(outcome.newShot)
        assertEquals(listOf(10, 1), tracker.log.map { it.shotNumber })
    }
}
