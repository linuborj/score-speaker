package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Test
import se.linusborjesson.scorespeaker.pipeline.Announcer
import se.linusborjesson.scorespeaker.pipeline.AnnouncementSource
import se.linusborjesson.scorespeaker.pipeline.Change
import se.linusborjesson.scorespeaker.pipeline.AnnouncerPolicy
import se.linusborjesson.scorespeaker.pipeline.Priority
import se.linusborjesson.scorespeaker.pipeline.RecordingAnnouncerSink
import se.linusborjesson.scorespeaker.pipeline.ShotAnnouncementKind
import se.linusborjesson.scorespeaker.pipeline.ShotProcessOutcome
import se.linusborjesson.scorespeaker.pipeline.ShotRecord
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.cells.TextValue
import se.linusborjesson.scorespeaker.processing.MissDetection
import se.linusborjesson.scorespeaker.processing.ShotMeasurement
import se.linusborjesson.scorespeaker.settings.OffsetStyle
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnouncerTest {

    private fun fixedClock(): () -> Long = { 1000L }

    @Test
    fun `score is announced via onShotOutcome — shot, mode, score with point decimal`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(1, "P", "7.6")))
        assertEquals(1, sink.list.size)
        assertEquals("Shot 1, practice, 7 point 6", sink.list[0].text)
        assertEquals(Priority.HIGH, sink.list[0].priority)
        assertEquals(
            ShotAnnouncementKind.SCORE,
            (sink.list[0].source as AnnouncementSource.FromShot).kind,
        )
    }

    @Test
    fun `score without practice mode omits the practice word`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(20, null, "7.6")))
        assertEquals("Shot 20, 7 point 6", sink.list[0].text)
    }

    @Test
    fun `Swedish speech strings produce a fully Swedish announcement`() {
        val sink = RecordingAnnouncerSink()
        val swedish = AnnouncerPolicy(speech = se.linusborjesson.scorespeaker.pipeline.SwedishSpeech)
        val announcer = Announcer(sink, swedish, fixedClock())
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(1, "P", "7.6")))
        assertEquals("Skott 1, provskott, 7 komma 6", sink.list[0].text)
    }

    @Test
    fun `policy options can suppress shot and mode`() {
        val sink = RecordingAnnouncerSink()
        val terse = AnnouncerPolicy(includeShotNumber = false, includeMode = false)
        val announcer = Announcer(sink, terse, fixedClock())
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(1, "P", "7.6")))
        assertEquals("7 point 6", sink.list[0].text)
    }

    @Test
    fun `Cell D change is suppressed in the change path — ShotTracker is authoritative`() {
        // This is the re-acquisition fix: ChangeTracker can spuriously
        // emit Change(null → "1P 7.6") after a detection gap. The Cell D
        // change path must stay silent or the user re-hears the same shot.
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        announcer.onChanges(listOf(
            Change("D", from = null, to = ScoreShotValue("1", mode = "P", score = "7.6"))
        ))
        assertEquals(emptyList(), sink.list)
    }

    @Test
    fun `re-announcing the same shot is suppressed by per-shot dedup`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        val record = shotRecord(1, "P", "7.6")
        announcer.onShotOutcome(ShotProcessOutcome(newShot = record))
        announcer.onShotOutcome(ShotProcessOutcome(newShot = record))
        assertEquals(1, sink.list.size, "second newShot for the same shot number should not re-announce")
    }

    @Test
    fun `status text is announced verbatim`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        announcer.onChanges(listOf(
            Change("F", from = null, to = TextValue("KLAR"))
        ))
        assertEquals("KLAR", sink.list[0].text)
        assertEquals(Priority.MEDIUM, sink.list[0].priority)
    }

    @Test
    fun `non-tracked cells are silent`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        // Even with a value change, a cell the policy doesn't know about
        // should produce nothing.
        announcer.onChanges(listOf(
            Change("A", from = null, to = TextValue("Tavla/Bana 9")),
            Change("B", from = null, to = TextValue("17.12.2025 15:28")),
            Change("H", from = null, to = TextValue("11 A Shooter")),
        ))
        assertEquals(emptyList(), sink.list)
    }

    @Test
    fun `multiple changes preserve order — Cell D no longer participates`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        // Two F changes show ordering; D goes silent (handled by ShotTracker).
        announcer.onChanges(listOf(
            Change("F", null, TextValue("KLAR")),
            Change("F", TextValue("KLAR"), TextValue("STOPP")),
        ))
        assertEquals(listOf("KLAR", "STOPP"), sink.list.map { it.text })
    }

    @Test
    fun `clock is consulted per announcement`() {
        val timestamps = mutableListOf(100L, 200L, 300L)
        val clock = { timestamps.removeFirst() }
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), clock)
        announcer.onChanges(listOf(Change("F", null, TextValue("KLAR"))))
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(1, null, "7.6")))
        assertEquals(listOf(100L, 200L), sink.list.map { it.timestamp })
    }

    // ----- onShotOutcome / measurement announcements -----

    @Test
    fun `new series resets the per-shot dedup — shot 1 announces again`() {
        // Regression: on Android nothing external calls reset(), so shot
        // numbers repeating in the NEXT series were suppressed as duplicates
        // — the score stayed silent while the (different-valued) measurement
        // spoke, heard in the field as a bare "1 o'clock" with no score.
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        val m1 = ShotMeasurement(score = 9.0, offsetXRings = 1.0, offsetYRings = 0.0, distanceRings = 1.0)
        val m2 = ShotMeasurement(score = 8.0, offsetXRings = -2.0, offsetYRings = 0.0, distanceRings = 2.0)

        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(1, "P", "9.0", m1)))
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(2, "P", "8.4")))
        // SIUS counter resets — new series, shot 1 again.
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(1, "P", "8.0", m2)))

        val scores = sink.list.filter {
            (it.source as? AnnouncementSource.FromShot)?.kind == ShotAnnouncementKind.SCORE
        }
        assertEquals(3, scores.size, "series-two shot 1 must announce its score")
        assertEquals("Shot 1, practice, 8 point 0", scores.last().text)
    }

    @Test
    fun `a miss is spoken as a miss, not as zero point zero`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        // Badge bottom-left: the shot went out low and to the left.
        val miss = MissDetection(shotNumber = 16, dx = -0.93, dy = 0.95)
        announcer.onShotOutcome(
            ShotProcessOutcome(newShot = shotRecord(16, null, "0.0", miss = miss)),
        )
        assertEquals(2, sink.list.size)
        assertEquals("Shot 16, miss", sink.list[0].text)
        assertEquals(Priority.HIGH, sink.list[0].priority)
        // Down-left badge -> roughly 7:30, spoken as the nearest hour.
        // Direction only, clock style — the badge is a bearing, not an offset.
        assertEquals("7 o'clock", sink.list[1].text)
        assertEquals(Priority.MEDIUM, sink.list[1].priority)
    }

    @Test
    fun `a miss direction is announced once, not on every update`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        val record = shotRecord(16, null, "0.0", miss = MissDetection(16, -0.93, 0.95))
        announcer.onShotOutcome(ShotProcessOutcome(newShot = record))
        announcer.onShotOutcome(ShotProcessOutcome(updatedShot = record))
        assertEquals(2, sink.list.size)
    }

    @Test
    fun `a miss never speaks a ring offset`() {
        val policy = AnnouncerPolicy(offsetStyle = OffsetStyle.DIRECTIONS, offsetDecimals = true)
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, policy, fixedClock())
        announcer.onShotOutcome(
            ShotProcessOutcome(newShot = shotRecord(16, null, "0.0", miss = MissDetection(16, -0.93, 0.95))),
        )
        // Even with DIRECTIONS configured, a miss stays a clock bearing —
        // "left 0.9, down 1.0" would dress a rim position up as a measurement.
        assertTrue(sink.list.none { it.text.contains("left") }, "spoke a ring offset for a miss")
        assertEquals("7 o'clock", sink.list[1].text)
    }

    private fun shotRecord(
        shotNumber: Int = 1,
        mode: String? = "P",
        score: String? = "7.6",
        measurement: ShotMeasurement? = null,
        miss: MissDetection? = null,
    ) = ShotRecord(
        shotNumber = shotNumber,
        mode = mode,
        score = score,
        measurement = measurement,
        firstSeenAt = 1000,
        lastUpdatedAt = 1000,
        miss = miss,
    )

    @Test
    fun `measurement-only outcome emits one MEDIUM utterance with point decimals`() {
        // Same shot, just gained a measurement on a later updatedShot —
        // newShot already fired previously (and was deduped). Just the
        // measurement announces here.
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        val measurement = ShotMeasurement(score = 7.6, offsetXRings = -3.1, offsetYRings = 1.0, distanceRings = 3.26)
        val record = shotRecord(measurement = measurement)
        // Mark score as already announced (mimic prior tick).
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(measurement = null)))
        sink.clear()

        announcer.onShotOutcome(ShotProcessOutcome(updatedShot = record))

        assertEquals(1, sink.list.size)
        with(sink.list[0]) {
            assertEquals("left 3 point 1, down 1 point 0", text)
            assertEquals(Priority.MEDIUM, priority)
            assertTrue(source is AnnouncementSource.FromShot)
            assertEquals(ShotAnnouncementKind.MEASUREMENT, (source as AnnouncementSource.FromShot).kind)
        }
    }

    @Test
    fun `newShot with measurement emits score then measurement, score first`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        val measurement = ShotMeasurement(score = 7.6, offsetXRings = -3.1, offsetYRings = 1.0, distanceRings = 3.26)
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(measurement = measurement)))

        assertEquals(2, sink.list.size)
        assertEquals("Shot 1, practice, 7 point 6", sink.list[0].text)
        assertEquals(Priority.HIGH, sink.list[0].priority)
        assertEquals("left 3 point 1, down 1 point 0", sink.list[1].text)
        assertEquals(Priority.MEDIUM, sink.list[1].priority)
    }

    @Test
    fun `newShot with no measurement announces only the score`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(measurement = null)))
        assertEquals(1, sink.list.size)
        assertEquals(
            ShotAnnouncementKind.SCORE,
            (sink.list[0].source as AnnouncementSource.FromShot).kind,
        )
    }

    @Test
    fun `bullseye measurement is suppressed — only score announces`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        val centred = ShotMeasurement(score = 10.9, offsetXRings = 0.01, offsetYRings = 0.0, distanceRings = 0.01)
        announcer.onShotOutcome(ShotProcessOutcome(newShot = shotRecord(measurement = centred)))
        // Only the score utterance; no offset for a centred dot.
        assertEquals(1, sink.list.size)
        assertEquals(
            ShotAnnouncementKind.SCORE,
            (sink.list[0].source as AnnouncementSource.FromShot).kind,
        )
    }

    @Test
    fun `same measurement on a later updatedShot is not re-announced`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        val m = ShotMeasurement(score = 7.6, offsetXRings = -3.1, offsetYRings = 1.0, distanceRings = 3.26)
        val record = shotRecord(measurement = m)
        announcer.onShotOutcome(ShotProcessOutcome(newShot = record))
        val before = sink.list.size
        announcer.onShotOutcome(ShotProcessOutcome(updatedShot = record.copy(lastUpdatedAt = 2000)))
        assertEquals(before, sink.list.size)
    }

    @Test
    fun `axis-only offsets format with one direction`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        announcer.onShotOutcome(
            ShotProcessOutcome(
                newShot = shotRecord(measurement = ShotMeasurement(7.0, offsetXRings = 0.0, offsetYRings = -2.5, distanceRings = 2.5)),
            ),
        )
        // Two utterances — score then measurement; check the measurement specifically.
        val measurementText = sink.list.last().text
        assertEquals("up 2 point 5", measurementText)
    }

    @Test
    fun `reset clears per-shot dedup so the same shot can be re-announced`() {
        val sink = RecordingAnnouncerSink()
        val announcer = Announcer(sink, AnnouncerPolicy(), fixedClock())
        val record = shotRecord(measurement = ShotMeasurement(7.6, -3.1, 1.0, 3.26))
        announcer.onShotOutcome(ShotProcessOutcome(newShot = record))
        announcer.reset()
        announcer.onShotOutcome(ShotProcessOutcome(newShot = record))
        // Each newShot emits score + measurement = 2 utterances; after reset, same again = 4 total.
        assertEquals(4, sink.list.size)
    }

    // ----- settings gating (announceScore / announceMeasurement) -----

    @Test
    fun `announceScore=false suppresses the score but keeps the offset`() {
        val sink = RecordingAnnouncerSink()
        val policy = AnnouncerPolicy(announceScore = false)
        val announcer = Announcer(sink, policy, fixedClock())
        announcer.onShotOutcome(
            ShotProcessOutcome(newShot = shotRecord(measurement = ShotMeasurement(7.6, -3.1, 1.0, 3.26))),
        )
        assertEquals(1, sink.list.size)
        assertEquals(ShotAnnouncementKind.MEASUREMENT, (sink.list[0].source as AnnouncementSource.FromShot).kind)
    }

    @Test
    fun `announceMeasurement=false suppresses the offset but keeps the score`() {
        val sink = RecordingAnnouncerSink()
        val policy = AnnouncerPolicy(announceMeasurement = false)
        val announcer = Announcer(sink, policy, fixedClock())
        announcer.onShotOutcome(
            ShotProcessOutcome(newShot = shotRecord(measurement = ShotMeasurement(7.6, -3.1, 1.0, 3.26))),
        )
        assertEquals(1, sink.list.size)
        assertEquals(ShotAnnouncementKind.SCORE, (sink.list[0].source as AnnouncementSource.FromShot).kind)
    }

    @Test
    fun `both gates off — a new shot says nothing`() {
        val sink = RecordingAnnouncerSink()
        val policy = AnnouncerPolicy(announceScore = false, announceMeasurement = false)
        val announcer = Announcer(sink, policy, fixedClock())
        announcer.onShotOutcome(
            ShotProcessOutcome(newShot = shotRecord(measurement = ShotMeasurement(7.6, -3.1, 1.0, 3.26))),
        )
        assertTrue(sink.list.isEmpty())
    }
}
