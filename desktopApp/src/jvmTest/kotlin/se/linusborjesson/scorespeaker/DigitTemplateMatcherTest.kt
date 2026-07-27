package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.ocr.DigitTemplateMatcher
import se.linusborjesson.scorespeaker.pipeline.fromBufferedImage
import se.linusborjesson.scorespeaker.pipeline.DetectedScreen
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.useMat
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.testdata.TestCase
import se.linusborjesson.scorespeaker.testdata.findCorpusDir
import se.linusborjesson.scorespeaker.testdata.findTestDataDir
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Exercise the template matcher's NCC recognition on the annotated Cell D
 * score regions — a self-contained round-trip: bootstrap templates from the
 * corpus's own glyphs, then recognise the same regions back.
 *
 * Bootstrap: take the score regions from all annotated cases, run
 * connected components on each, pair them with the expected digits
 * (left-to-right), and register them as templates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DigitTemplateMatcherTest {

    private val testDataDir = findTestDataDir()
    private val corpusDir = findCorpusDir()
    private val templateFile = File(testDataDir, "masked-template.png")

    @BeforeAll
    fun setup() {
        assumeTrue(testDataDir.exists())
        assumeTrue(templateFile.exists())
    }

    @Test
    fun `template matcher recognises bootstrap-case scores`() {
        val cases = annotatedScoreCases()
        assumeTrue(cases.isNotEmpty()) { "no annotated cases with score" }

        val matcher = DigitTemplateMatcher(normalisedHeight = 60, minScore = 0.45)
        bootstrap(matcher, cases)

        var ok = 0
        for ((case, expected) in cases) {
            val cellMat = scoreRegionMat(case)
            val recognised = cellMat.useMat { matcher.recognise(it) } ?: "(null)"
            val expectedDigits = expected.score!!.replace(".", "")
            val recognisedDigits = recognised.filter { it.isDigit() }
            val match = recognisedDigits.contains(expectedDigits)
            if (match) ok++
            println("  ${case.displayName}: expected ${expected.score}  got '$recognised' (digits '$recognisedDigits') ${if (match) "✓" else "✗"}")
        }
        println()
        println("  Template matcher: $ok/${cases.size} cases match expected digits")
    }

    @Test
    fun `template matcher per-call speed`() {
        val cases = annotatedScoreCases()
        assumeTrue(cases.isNotEmpty())

        val matcher = DigitTemplateMatcher()
        bootstrap(matcher, cases)
        val (firstCase, _) = cases.first()
        val scoreMat = scoreRegionMat(firstCase)

        repeat(3) { scoreMat.clone().useMat { matcher.recognise(it) } }

        val n = 30
        var tmTotal = 0L
        repeat(n) {
            val (_, dt) = timed { scoreMat.clone().useMat { matcher.recognise(it) } }
            tmTotal += dt
        }
        scoreMat.release()

        println()
        println("=== Per-call matcher cost (score region, ${cases.first().first.displayName}) ===")
        println("  Template matcher: ${(tmTotal / n).nanoseconds} per call")
        println()
    }

    /** Bootstrap templates by connected-components on each case's score region. */
    private fun bootstrap(matcher: DigitTemplateMatcher, cases: List<Pair<TestCase, ScoreShotValue>>) {
        for ((case, expected) in cases) {
            val expectedDigits = expected.score!!.replace(".", "")  // "7.6" → "76"
            val scoreMat = scoreRegionMat(case)
            val glyphs = scoreMat.useMat { extractGlyphsLeftToRight(it) }
            // Glyph count includes the decimal-point blob. Filter it out by area:
            // it's the smallest component.
            // Take the N tallest components (digits, dropping the decimal point blob).
            val digitGlyphs = glyphs
                .sortedByDescending { it.second.height }
                .take(expectedDigits.length)
                .sortedBy { it.second.x }
            if (digitGlyphs.size != expectedDigits.length) continue
            for ((i, dg) in digitGlyphs.withIndex()) {
                val ch = expectedDigits[i]
                matcher.registerCharacter(ch, dg.first)
            }
        }
    }

    /** Returns (cropMat, bboxRect) for each connected component in [scoreMat]. */
    private fun extractGlyphsLeftToRight(scoreMat: Mat): List<Pair<Mat, Rect>> {
        val gray = Mat()
        val binary = Mat()
        try {
            if (scoreMat.channels() > 1) Imgproc.cvtColor(scoreMat, gray, Imgproc.COLOR_BGR2GRAY) else scoreMat.copyTo(gray)
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val whitePx = Core.countNonZero(binary)
            val total = binary.rows() * binary.cols()
            if (whitePx > total / 2) Core.bitwise_not(binary, binary)

            val labels = Mat(); val stats = Mat(); val centroids = Mat()
            val count = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids)
            val results = mutableListOf<Pair<Mat, Rect>>()
            val cellArea = binary.rows() * binary.cols()
            for (i in 1 until count) {
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val a = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()
                if (a < cellArea / 1000) continue
                if (w >= binary.cols() - 2 || h >= binary.rows() - 2) continue
                if (w > h * 2.5 || h > w * 8) continue
                val rect = Rect(x, y, w, h)
                results += Mat(binary, rect).clone() to rect
            }
            labels.release(); stats.release(); centroids.release()
            return results.sortedBy { it.second.x }
        } finally {
            gray.release(); binary.release()
        }
    }

    /** Just the score-region Mat of [case] (right ~70% of cell D, padded). */
    private fun scoreRegionMat(case: TestCase): Mat {
        val cellMat = cellMatFor(case)
        return cellMat.useMat { full ->
            val w = full.cols(); val h = full.rows()
            val padX = (w * 0.04).toInt().coerceAtLeast(2)
            val padY = (h * 0.08).toInt().coerceAtLeast(2)
            val rect = Rect((w * 0.30).toInt(), padY, w - (w * 0.30).toInt() - padX, h - 2 * padY)
            Mat(full, rect).clone()
        }
    }

    private fun cellMatFor(case: TestCase): Mat {
        val sourceImage = ImageIO.read(case.sourceFile)
        val corners = case.loadAnnotations().screenCorners!!
        return ImageSource.fromBufferedImage(sourceImage, sourcePath = case.sourceFile.absolutePath).use { source ->
            val detected = DetectedScreen(source, corners.toQuadrilateral(), 1.0f, "manual")
            detected.rectifyAtDetectedResolution().use { view ->
                view.withSiusCells().extractCellAsMat("D")!!
            }
        }
    }

    private fun annotatedScoreCases(): List<Pair<TestCase, ScoreShotValue>> =
        TestCase.findAll(corpusDir).mapNotNull { case ->
            val expected = case.loadAnnotations().expectedValues["D"] as? ScoreShotValue
            if (expected?.score != null && case.loadAnnotations().screenCorners != null) case to expected else null
        }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - t0)
    }
}

