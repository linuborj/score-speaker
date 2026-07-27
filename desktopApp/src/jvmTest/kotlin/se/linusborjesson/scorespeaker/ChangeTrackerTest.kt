package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Test
import se.linusborjesson.scorespeaker.pipeline.ChangeTracker
import se.linusborjesson.scorespeaker.pipeline.Reading
import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.cells.ScoreValue
import se.linusborjesson.scorespeaker.cells.TextValue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeTrackerTest {

    @Test
    fun `first reading emits one change per non-null cell`() {
        val tracker = ChangeTracker()
        val changes = tracker.process(
            Reading(0, mapOf("F" to ScoreValue("6.9"), "J" to TextValue("KLAR")))
        )
        assertEquals(2, changes.size)
        assertTrue(changes.all { it.from == null })
    }

    @Test
    fun `null cells in first reading are not changes`() {
        val tracker = ChangeTracker()
        val changes = tracker.process(
            Reading(0, mapOf("F" to ScoreValue("6.9"), "G" to null))
        )
        assertEquals(listOf("F"), changes.map { it.cellName })
    }

    @Test
    fun `same reading twice produces no changes the second time`() {
        val tracker = ChangeTracker()
        val r = Reading(0, mapOf("F" to ScoreValue("6.9")))
        tracker.process(r)
        assertEquals(emptyList(), tracker.process(r.copy(timestamp = 1)))
    }

    @Test
    fun `numeric change emits exactly one Change with old and new`() {
        val tracker = ChangeTracker()
        tracker.process(Reading(0, mapOf("F" to ScoreValue("6.9"))))
        val changes = tracker.process(Reading(1, mapOf("F" to ScoreValue("10.4"))))
        assertEquals(1, changes.size)
        with(changes[0]) {
            assertEquals("F", cellName)
            assertEquals(ScoreValue("6.9"), from)
            assertEquals(ScoreValue("10.4"), to)
        }
    }

    @Test
    fun `cell appearing emits a change with null from`() {
        val tracker = ChangeTracker()
        tracker.process(Reading(0, mapOf("F" to null)))
        val changes = tracker.process(Reading(1, mapOf("F" to ScoreValue("6.9"))))
        assertEquals(1, changes.size)
        assertEquals(null, changes[0].from)
        assertEquals(ScoreValue("6.9"), changes[0].to)
    }

    @Test
    fun `single null reading after a value preserves the held value`() {
        // Null-hold: one missed extract is not enough to invalidate state —
        // would otherwise re-announce on every transient OCR glitch.
        val tracker = ChangeTracker()
        tracker.process(Reading(0, mapOf("F" to ScoreValue("6.9"))))
        val changes = tracker.process(Reading(1, mapOf("F" to null)))
        assertEquals(emptyList(), changes)
    }

    @Test
    fun `null streak reaching threshold invalidates held value`() {
        val tracker = ChangeTracker(nullHoldThreshold = 3)
        tracker.process(Reading(0, mapOf("F" to ScoreValue("6.9"))))
        // Frames 1 and 2 (streak 1, 2) — sub-threshold, no change.
        assertEquals(emptyList(), tracker.process(Reading(1, mapOf("F" to null))))
        assertEquals(emptyList(), tracker.process(Reading(2, mapOf("F" to null))))
        // Frame 3 (streak 3) hits the threshold — emit held→null.
        val changes = tracker.process(Reading(3, mapOf("F" to null)))
        assertEquals(1, changes.size)
        assertEquals(ScoreValue("6.9"), changes[0].from)
        assertEquals(null, changes[0].to)
    }

    @Test
    fun `value returning during null streak emits no change if same as held`() {
        val tracker = ChangeTracker(nullHoldThreshold = 5)
        tracker.process(Reading(0, mapOf("F" to ScoreValue("6.9"))))
        tracker.process(Reading(1, mapOf("F" to null)))
        tracker.process(Reading(2, mapOf("F" to null)))
        val changes = tracker.process(Reading(3, mapOf("F" to ScoreValue("6.9"))))
        assertEquals(emptyList(), changes)
    }

    @Test
    fun `value returning during null streak emits one change if differing`() {
        val tracker = ChangeTracker(nullHoldThreshold = 5)
        tracker.process(Reading(0, mapOf("F" to ScoreValue("6.9"))))
        tracker.process(Reading(1, mapOf("F" to null)))
        tracker.process(Reading(2, mapOf("F" to null)))
        // Direct held→new transition — no transient null change interposed.
        val changes = tracker.process(Reading(3, mapOf("F" to ScoreValue("7.4"))))
        assertEquals(1, changes.size)
        assertEquals(ScoreValue("6.9"), changes[0].from)
        assertEquals(ScoreValue("7.4"), changes[0].to)
    }

    @Test
    fun `null streak resets after any non-null read`() {
        val tracker = ChangeTracker(nullHoldThreshold = 3)
        tracker.process(Reading(0, mapOf("F" to ScoreValue("6.9"))))
        tracker.process(Reading(1, mapOf("F" to null)))    // streak 1
        tracker.process(Reading(2, mapOf("F" to null)))    // streak 2
        tracker.process(Reading(3, mapOf("F" to ScoreValue("6.9")))) // resets streak
        tracker.process(Reading(4, mapOf("F" to null)))    // streak 1
        tracker.process(Reading(5, mapOf("F" to null)))    // streak 2 — still sub-threshold
        // Would have hit threshold at frame 3 without the reset; with reset, fires at frame 6.
        val changes = tracker.process(Reading(6, mapOf("F" to null)))
        assertEquals(1, changes.size)
        assertEquals(ScoreValue("6.9"), changes[0].from)
        assertEquals(null, changes[0].to)
    }

    @Test
    fun `cells with different types in same key emit a change`() {
        val tracker = ChangeTracker()
        tracker.process(Reading(0, mapOf("F" to ScoreValue("6.9"))))
        val changes = tracker.process(Reading(1, mapOf<String, CellValue?>("F" to TextValue("ERROR"))))
        assertEquals(1, changes.size)
    }

    @Test
    fun `reset clears state so next reading re-emits as if first`() {
        val tracker = ChangeTracker()
        tracker.process(Reading(0, mapOf("F" to ScoreValue("6.9"))))
        tracker.reset()
        val changes = tracker.process(Reading(1, mapOf("F" to ScoreValue("6.9"))))
        assertEquals(1, changes.size, "after reset, the same value should be reported as a change")
    }

    @Test
    fun `independent cells change independently`() {
        val tracker = ChangeTracker()
        tracker.process(Reading(0, mapOf(
            "F" to ScoreValue("6.9"),
            "J" to TextValue("KLAR"),
        )))
        val changes = tracker.process(Reading(1, mapOf(
            "F" to ScoreValue("6.9"),   // unchanged
            "J" to TextValue("READY"),  // changed
        )))
        assertEquals(listOf("J"), changes.map { it.cellName })
    }
}
