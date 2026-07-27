package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Test
import se.linusborjesson.scorespeaker.pipeline.ShotQueries
import se.linusborjesson.scorespeaker.pipeline.ShotRecord
import se.linusborjesson.scorespeaker.processing.ShotMeasurement
import kotlin.test.assertEquals

class ShotQueriesTest {

    private fun shot(
        number: Int,
        score: String? = "7.6",
        measurement: ShotMeasurement? = null,
    ) = ShotRecord(
        shotNumber = number,
        mode = "P",
        score = score,
        measurement = measurement,
        firstSeenAt = number * 1000L,
        lastUpdatedAt = number * 1000L,
    )

    private fun m(x: Double, y: Double) =
        ShotMeasurement(score = 10.0, offsetXRings = x, offsetYRings = y, distanceRings = 0.0)

    @Test
    fun `last shot speaks number and score with spoken decimal`() {
        val log = listOf(shot(1, "9.1"), shot(2, "10.4"))
        assertEquals("Shot 2, 10 point 4", ShotQueries.lastShotText(log))
    }

    @Test
    fun `last shot includes its error when measured`() {
        val log = listOf(shot(7, "7.6", measurement = m(-0.4, 0.2)))
        assertEquals("Shot 7, 7 point 6, left 0 point 4, down 0 point 2", ShotQueries.lastShotText(log))
    }

    @Test
    fun `last shot dead centre says centred`() {
        val log = listOf(shot(4, "10.9", measurement = m(0.0, 0.0)))
        assertEquals("Shot 4, 10 point 9, centred", ShotQueries.lastShotText(log))
    }

    @Test
    fun `last shot on empty log`() {
        assertEquals("No shots yet", ShotQueries.lastShotText(emptyList()))
    }

    @Test
    fun `last shot when the score never OCRd`() {
        assertEquals("Shot 3, no score read", ShotQueries.lastShotText(listOf(shot(3, score = null))))
    }

    @Test
    fun `average error averages the last five measurements with direction words`() {
        // Six shots; the first must NOT contribute (it would flip the sign).
        val log = listOf(
            shot(1, measurement = m(9.0, 9.0)),
            shot(2, measurement = m(-0.4, -0.1)),
            shot(3, measurement = m(-0.2, -0.3)),
            shot(4, measurement = m(-0.3, -0.2)),
            shot(5, measurement = m(-0.4, -0.3)),
            shot(6, measurement = m(-0.2, -0.1)),
        )
        // avg x = -0.3, avg y = -0.2 → left 0.3, up 0.2
        assertEquals(
            "Average error last 5 shots: left 0 point 3, up 0 point 2",
            ShotQueries.averageErrorText(log),
        )
    }

    @Test
    fun `average error counts only shots that carry measurements`() {
        val log = listOf(
            shot(1, measurement = m(0.5, 0.0)),
            shot(2, measurement = null),
            shot(3, measurement = m(0.3, 0.0)),
        )
        assertEquals(
            "Average error last 2 shots: right 0 point 4",
            ShotQueries.averageErrorText(log),
        )
    }

    @Test
    fun `scatter that cancels out is centred`() {
        val log = listOf(
            shot(1, measurement = m(0.4, -0.3)),
            shot(2, measurement = m(-0.4, 0.3)),
        )
        assertEquals("Average error last 2 shots: centred", ShotQueries.averageErrorText(log))
    }

    @Test
    fun `average error with no measurements at all`() {
        assertEquals("No measurements yet", ShotQueries.averageErrorText(listOf(shot(1))))
    }

    @Test
    fun `Swedish speech strings localize the whole answer`() {
        val log = listOf(shot(7, "7.6", measurement = m(-0.4, 0.2)))
        assertEquals(
            "Skott 7, 7 komma 6, vänster 0 komma 4, ner 0 komma 2",
            ShotQueries.lastShotText(log, se.linusborjesson.scorespeaker.pipeline.SwedishSpeech),
        )
        assertEquals(
            "Snittfel senaste 1 skotten: vänster 0 komma 4, ner 0 komma 2",
            ShotQueries.averageErrorText(log, speech = se.linusborjesson.scorespeaker.pipeline.SwedishSpeech),
        )
    }
}
