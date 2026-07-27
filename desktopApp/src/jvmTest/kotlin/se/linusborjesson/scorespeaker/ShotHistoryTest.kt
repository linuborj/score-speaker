package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Test
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.db.DriverFactory
import se.linusborjesson.scorespeaker.db.createDatabase
import se.linusborjesson.scorespeaker.pipeline.HistoryShot
import se.linusborjesson.scorespeaker.pipeline.Reading
import se.linusborjesson.scorespeaker.pipeline.ShotHistory
import se.linusborjesson.scorespeaker.pipeline.ShotRecorder
import se.linusborjesson.scorespeaker.pipeline.ShotTracker
import se.linusborjesson.scorespeaker.pipeline.averageSince
import se.linusborjesson.scorespeaker.pipeline.deriveSessions
import se.linusborjesson.scorespeaker.pipeline.summarize
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShotHistoryTest {

    private val minute = 60_000L

    private fun shot(n: Int, score: Double?, at: Long) =
        HistoryShot(shotNumber = n, score = score, offsetXRings = null, offsetYRings = null, observedAt = at)

    @Test
    fun `empty stream yields no sessions`() {
        assertEquals(emptyList(), deriveSessions(emptyList()))
    }

    @Test
    fun `one contiguous run is a single session`() {
        val shots = (1..10).map { shot(it, 9.0 + it * 0.05, it * minute) }
        val sessions = deriveSessions(shots)
        assertEquals(1, sessions.size)
        assertEquals(10, sessions[0].shotCount)
        assertEquals(1, sessions[0].index)
    }

    @Test
    fun `a shot-number reset starts a new session`() {
        val first = (1..5).map { shot(it, 9.5, it * minute) }
        val second = (1..3).map { shot(it, 9.0, (100 + it) * minute) }
        val sessions = deriveSessions(first + second)
        assertEquals(2, sessions.size)
        assertEquals(listOf(5, 3), sessions.map { it.shotCount })
        assertEquals(listOf(1, 2), sessions.map { it.index })
    }

    @Test
    fun `an inactivity gap starts a new session even when shot numbers keep climbing`() {
        // SIUS counter never reset, but the shooter came back hours later.
        val before = (1..5).map { shot(it, 9.5, it * minute) }
        val after = (6..9).map { shot(it, 9.7, it * minute + 60 * minute) } // +60min jump after shot 5
        val sessions = deriveSessions(before + after, gapMillis = 30 * minute)
        assertEquals(2, sessions.size)
        assertEquals(listOf(5, 4), sessions.map { it.shotCount })
    }

    @Test
    fun `gap just under the threshold stays one session`() {
        val a = shot(1, 9.5, 0)
        val b = shot(2, 9.6, 29 * minute)
        val sessions = deriveSessions(listOf(a, b), gapMillis = 30 * minute)
        assertEquals(1, sessions.size)
    }

    @Test
    fun `session stats — total, average, best`() {
        val shots = listOf(shot(1, 9.0, minute), shot(2, 10.0, 2 * minute), shot(3, 9.5, 3 * minute))
        val s = deriveSessions(shots).single()
        assertEquals(28.5, s.totalScore, 1e-9)
        assertEquals(9.5, s.averageScore!!, 1e-9)
        assertEquals(10.0, s.bestScore!!)
    }

    @Test
    fun `missing shot numbers are detected within a session`() {
        // observed 1,2,5 — 3 and 4 were missed (look-away)
        val shots = listOf(shot(1, 9.0, minute), shot(2, 9.2, 2 * minute), shot(5, 9.8, 3 * minute))
        val s = deriveSessions(shots).single()
        assertEquals(listOf(3, 4), s.missingShotNumbers)
    }

    @Test
    fun `unscored shots count toward shotCount but not the average`() {
        val shots = listOf(shot(1, 9.0, minute), shot(2, null, 2 * minute), shot(3, 9.4, 3 * minute))
        val s = deriveSessions(shots).single()
        assertEquals(3, s.shotCount)
        assertEquals(9.2, s.averageScore!!, 1e-9)
    }

    @Test
    fun `summarize aggregates across sessions`() {
        val s1 = (1..5).map { shot(it, 9.0, it * minute) }
        val s2 = (1..5).map { shot(it, 10.0, (100 + it) * minute) }
        val stats = summarize(deriveSessions(s1 + s2))
        assertEquals(2, stats.sessionCount)
        assertEquals(10, stats.totalShots)
        assertEquals(9.5, stats.overallAverage!!, 1e-9)
        assertEquals(10.0, stats.bestSessionAverage!!, 1e-9)
    }

    @Test
    fun `averageSince filters by session end time`() {
        val old = (1..5).map { shot(it, 8.0, it * minute) }                 // ends ~5min
        val recent = (1..5).map { shot(it, 10.0, (1000 + it) * minute) }     // ends ~1005min
        val sessions = deriveSessions(old + recent)
        // window starting at 500min keeps only the recent session
        assertEquals(10.0, averageSince(sessions, 500 * minute)!!, 1e-9)
    }

    @Test
    fun `reads back what ShotRecorder wrote and derives sessions end-to-end`() {
        val db = createDatabase(DriverFactory()) // in-memory
        val recorder = ShotRecorder(db)
        val tracker = ShotTracker(confirmationFrames = 1)
        // session 1: shots 1..3
        (1..3).forEach { n ->
            recorder.record(tracker.process(Reading(n * minute, mapOf("D" to ScoreShotValue("$n", "P", "9.${n}")))))
        }
        // session 2: reset to shot 1
        recorder.record(tracker.process(Reading(200 * minute, mapOf("D" to ScoreShotValue("1", "P", "10.4")))))

        val sessions = ShotHistory(db).sessions()
        assertEquals(2, sessions.size)
        assertEquals(listOf(3, 1), sessions.map { it.shotCount })
        assertTrue(sessions[1].shots.first().score == 10.4)
    }
}
