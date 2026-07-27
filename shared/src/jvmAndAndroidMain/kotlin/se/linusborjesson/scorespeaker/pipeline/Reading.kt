package se.linusborjesson.scorespeaker.pipeline

import se.linusborjesson.scorespeaker.cells.CellValue

/**
 * Result of reading one frame: the extracted cell values, keyed by cell name.
 */
data class Reading(
    val timestamp: Long,
    val cells: Map<String, CellValue?>,
)

/** A semantically-meaningful change between consecutive readings. */
data class Change(
    val cellName: String,
    val from: CellValue?,
    val to: CellValue?,
)

/**
 * Tracks the last *trusted* reading per cell and emits semantic changes.
 *
 * A per-cell `null` reading is treated as "no new information" and the last
 * known good value is held — single-frame read glitches (glare, blur, partial
 * extract) no longer emit spurious "cell disappeared" → "cell reappeared"
 * pairs. The held value is invalidated only after [nullHoldThreshold]
 * consecutive nulls, at which point a `held → null` change is emitted so
 * downstream consumers learn the cell really did disappear.
 *
 * Uses [CellValue.matches] for semantic comparison, not string equality —
 * presentation-level read variation doesn't trigger spurious changes.
 *
 * Not thread-safe — drive from a single coroutine/thread.
 *
 * @param nullHoldThreshold Consecutive null reads required to invalidate a
 *   held value. Default 5: at ~10–15 fps that's ~0.3–0.5 s, enough to absorb
 *   single-frame read glitches between valid readings. The tracker is only
 *   fed when detection itself succeeds (see `FrameAnalyzer`); detection
 *   failures don't increment the streak, they freeze it.
 */
class ChangeTracker(
    private val nullHoldThreshold: Int = 5,
) {
    private val held = mutableMapOf<String, CellValue>()
    private val nullStreaks = mutableMapOf<String, Int>()

    fun process(reading: Reading): List<Change> {
        val changes = mutableListOf<Change>()
        val keys = held.keys + reading.cells.keys
        for (key in keys) {
            val prev = held[key]
            val curr = reading.cells[key]

            if (curr != null) {
                nullStreaks[key] = 0
                if (prev == null || !curr.matches(prev)) {
                    changes += Change(key, prev, curr)
                    held[key] = curr
                }
            } else {
                val streak = (nullStreaks[key] ?: 0) + 1
                nullStreaks[key] = streak
                if (prev != null && streak >= nullHoldThreshold) {
                    changes += Change(key, prev, null)
                    held.remove(key)
                }
            }
        }
        return changes
    }

    fun reset() {
        held.clear()
        nullStreaks.clear()
    }
}
