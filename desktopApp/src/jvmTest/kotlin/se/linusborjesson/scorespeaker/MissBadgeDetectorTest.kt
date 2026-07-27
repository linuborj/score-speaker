package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Mat
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.ocr.DigitTemplateMatcher
import se.linusborjesson.scorespeaker.pipeline.Announcer
import se.linusborjesson.scorespeaker.pipeline.AnnouncerPolicy
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.pipeline.Reading
import se.linusborjesson.scorespeaker.pipeline.RecordingAnnouncerSink
import se.linusborjesson.scorespeaker.pipeline.ShotTracker
import se.linusborjesson.scorespeaker.pipeline.DetectedScreen
import se.linusborjesson.scorespeaker.pipeline.ImageBridge
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.ScreenDetector
import se.linusborjesson.scorespeaker.pipeline.fromBufferedImage
import se.linusborjesson.scorespeaker.processing.MissBadgeDetector
import se.linusborjesson.scorespeaker.processing.MultiScaleMatcher
import se.linusborjesson.scorespeaker.processing.loadAlphabetFromDirectory
import se.linusborjesson.scorespeaker.testdata.TestCase
import se.linusborjesson.scorespeaker.testdata.findCorpusDir
import se.linusborjesson.scorespeaker.testdata.findTestDataDir
import java.io.File
import javax.imageio.ImageIO

/**
 * The miss detector over the whole corpus: it must fire on the two
 * off-target shots and stay silent on every scored shot and on the blank
 * display. Silence on a real shot is the property that matters most —
 * a spurious "miss" is a confidently wrong announcement.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MissBadgeDetectorTest {

    private val corpusDir = findCorpusDir()
    private val testDataDir = findTestDataDir()

    private fun detector(): MissBadgeDetector? {
        val matcher = DigitTemplateMatcher(minScore = 0.5)
            .takeIf { it.loadAlphabetFromDirectory(File(testDataDir, "glyphs/shot-font")) }
            ?: return null
        return MissBadgeDetector().apply { shotTemplateMatcher = matcher }
    }

    @Test
    fun firesOnMissesAndNowhereElse() {
        CoordinateTransform.ensureOpenCv()
        val cases = TestCase.findAll(corpusDir)
        assumeTrue(cases.isNotEmpty(), "corpus absent")
        val missDetector = detector()
        assumeTrue(missDetector != null, "shot-font alphabet absent")

        var misses = 0
        var scored = 0
        for (case in cases.sortedBy { it.directory.name }) {
            val annotation = case.loadAnnotations().expectedValues["D"] as? ScoreShotValue ?: continue
            val shot = annotation.shot.toIntOrNull() ?: continue
            val expectMiss = annotation.score?.toDoubleOrNull() == 0.0
            val cell = targetCellMat(case) ?: continue
            try {
                val detected = missDetector!!.detect(cell, shot)
                val name = case.directory.name.take(8)
                if (expectMiss) {
                    misses++
                    assertNotNull(detected, "$name: expected a miss for shot $shot, got null")
                    assertEquals(shot, detected!!.shotNumber, "$name: wrong shot number")
                } else {
                    scored++
                    assertNull(detected, "$name: scored shot $shot reported as a miss")
                }
            } finally {
                cell.release()
            }
        }
        assertTrue(misses >= 2, "corpus should hold at least two misses, found $misses")
        assertTrue(scored >= 15, "expected the scored shots as negatives, found $scored")
    }

    /** The badge must agree with the reader — a mismatched shot is refused. */
    @Test
    fun refusesWhenBadgeDisagreesWithReader() {
        CoordinateTransform.ensureOpenCv()
        val missDetector = detector()
        assumeTrue(missDetector != null, "shot-font alphabet absent")
        val case = TestCase.findAll(corpusDir).firstOrNull {
            (it.loadAnnotations().expectedValues["D"] as? ScoreShotValue)?.score?.toDoubleOrNull() == 0.0
        }
        assumeTrue(case != null, "no miss case in corpus")
        val cell = targetCellMat(case!!) ?: return
        try {
            val shot = (case.loadAnnotations().expectedValues["D"] as ScoreShotValue).shot.toInt()
            assertNotNull(missDetector!!.detect(cell, shot))
            assertNull(
                missDetector.detect(cell, shot + 1),
                "badge read must gate on the reader's shot number",
            )
        } finally {
            cell.release()
        }
    }

    /** Without the alphabet the detector refuses rather than guessing. */
    @Test
    fun refusesWithoutGlyphConfirmation() {
        CoordinateTransform.ensureOpenCv()
        val case = TestCase.findAll(corpusDir).firstOrNull {
            (it.loadAnnotations().expectedValues["D"] as? ScoreShotValue)?.score?.toDoubleOrNull() == 0.0
        }
        assumeTrue(case != null, "no miss case in corpus")
        val cell = targetCellMat(case!!) ?: return
        try {
            val shot = (case.loadAnnotations().expectedValues["D"] as ScoreShotValue).shot.toInt()
            assertNull(MissBadgeDetector().detect(cell, shot))
        } finally {
            cell.release()
        }
    }

    /**
     * The whole chain on a real off-target frame: badge → tracker → speech.
     * The pre-existing behaviour for this frame was total silence, so this
     * is the test that says the feature exists.
     */
    @Test
    fun `a real miss frame is announced end to end`() {
        CoordinateTransform.ensureOpenCv()
        val missDetector = detector()
        assumeTrue(missDetector != null, "shot-font alphabet absent")
        // Shot 16 of the 2026-08-01 session: badge bottom-left, score 0.0.
        val case = TestCase.findAll(corpusDir).firstOrNull { it.directory.name.startsWith("0be5f71d") }
        assumeTrue(case != null, "miss case 0be5f71d absent")
        val cell = targetCellMat(case!!) ?: return
        try {
            val miss = missDetector!!.detect(cell, 16)
            assertNotNull(miss, "expected a miss on shot 16")

            val sink = RecordingAnnouncerSink()
            val announcer = Announcer(sink, AnnouncerPolicy()) { 1000L }
            val tracker = ShotTracker(confirmationFrames = 1)
            val reading = Reading(1000L, mapOf("D" to ScoreShotValue("16", mode = null, score = "0.0")))
            val outcome = tracker.process(reading, measurement = null, miss = miss)
            announcer.onShotOutcome(outcome)

            assertEquals(
                listOf("Shot 16, miss", "7 o'clock"),
                sink.list.map { it.text },
                "a missed shot should be spoken, with the direction it went out",
            )
            // The miss must not masquerade as a measurement anywhere.
            assertNull(outcome.newShot?.measurement, "a miss has no ring measurement")
        } finally {
            cell.release()
        }
    }

    private fun targetCellMat(case: TestCase): Mat? {
        val sourceImage = ImageIO.read(case.sourceFile) ?: return null
        val corners = case.loadAnnotations().screenCorners
        return ImageSource.fromBufferedImage(sourceImage, sourcePath = case.sourceFile.absolutePath).use { source ->
            val detected = if (corners != null) {
                DetectedScreen(source, corners.toQuadrilateral(), 1.0f, "manual")
            } else {
                detector.detect(source) ?: return@use null
            }
            detected.rectifyAtDetectedResolution().use { view ->
                view.withSiusCells().extractCellAsMat("TARGET")
            }
        }
    }

    private val detector: ScreenDetector by lazy {
        CoordinateTransform.ensureOpenCv()
        MultiScaleMatcher(scales = listOf(0.3, 0.5, 0.7, 1.0), maxFeatures = 500, minInliers = 8, maxInputWidth = 2500)
            .apply { loadTemplate(ImageBridge.toMat(ImageIO.read(File(findTestDataDir(), "masked-template.png")))) }
    }
}
