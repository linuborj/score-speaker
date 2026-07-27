package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Mat
import org.opencv.core.Scalar
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.pipeline.DetectedScreen
import se.linusborjesson.scorespeaker.pipeline.ImageBridge
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.ScreenDetector
import se.linusborjesson.scorespeaker.pipeline.fromBufferedImage
import se.linusborjesson.scorespeaker.processing.GreenDotDetector
import se.linusborjesson.scorespeaker.processing.MultiScaleMatcher
import se.linusborjesson.scorespeaker.testdata.TestCase
import se.linusborjesson.scorespeaker.testdata.findCorpusDir
import se.linusborjesson.scorespeaker.testdata.findTestDataDir
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Diagnostic: enumerate *every* green blob in each case's TARGET cell,
 * with the production size band deliberately widened, and print its
 * equivalent radius as a fraction of cell width plus its centroid in
 * normalised cell coordinates.
 *
 * The question this answers: a missed shot is drawn not on the target face
 * but as a small green badge at the cell edge, in the direction the shot
 * went off. Is that badge separable from a real shot marker by size and
 * position — i.e. can "the shot missed" be a *positive* detection rather
 * than an inference from the dot's absence?
 *
 * Read-only; safe to run in the suite.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GreenBlobSurvey {

    private val corpusDir = findCorpusDir()

    @Test
    fun surveyGreenBlobs() {
        CoordinateTransform.ensureOpenCv()
        val cases = TestCase.findAll(corpusDir).sortedBy { it.directory.name }
        assumeTrue(cases.isNotEmpty(), "corpus absent")

        // Wide open: 0.2%..25% of width as equivalent radius, so nothing is
        // filtered out before we have looked at it.
        val permissive = GreenDotDetector(minRadiusRatio = 0.002, maxRadiusRatio = 0.25)
        val production = GreenDotDetector()

        for (case in cases) {
            val annotation = case.loadAnnotations().expectedValues["D"] as? ScoreShotValue
            val label = annotation?.let { "${it.shot}${it.mode ?: ""} score=${it.score}" } ?: "unannotated"
            val miss = annotation?.score?.toDoubleOrNull() == 0.0
            val cell = targetCellMat(case) ?: continue
            try {
                val all = permissive.candidates(cell)
                val accepted = production.detect(cell)
                val w = cell.cols().toDouble()
                val h = cell.rows().toDouble()
                println("${case.directory.name.take(8)} ${if (miss) "MISS" else "    "} $label  cell=${cell.cols()}x${cell.rows()}")
                for (blob in all.sortedByDescending { it.pixelCount }) {
                    val radiusRatio = sqrt(blob.pixelCount / PI) / w
                    val nx = blob.centroidX / w
                    val ny = blob.centroidY / h
                    // Distance from the cell centre in half-widths: ~0 is the
                    // middle of the target face, ~1 is the edge.
                    val edge = maxOf(kotlin.math.abs(nx - 0.5), kotlin.math.abs(ny - 0.5)) * 2
                    println(
                        "    px=${blob.pixelCount} r/W=${"%.4f".format(radiusRatio)}" +
                            " at (${"%.3f".format(nx)}, ${"%.3f".format(ny)}) edge=${"%.2f".format(edge)}" +
                            " box=${blob.boundingRect.width}x${blob.boundingRect.height}",
                    )
                }
                if (all.isEmpty()) println("    (no green blobs at all)")
                println("    production detect(): ${accepted?.let { "px=${it.pixelCount}" } ?: "null"}")
                // Dump rim blobs so the badge's internals can be inspected.
                // Opt-in: a test shouldn't scatter files by default.
                val badgeDir = System.getenv("SCORE_SPEAKER_BADGE_DIR")
                if (miss && badgeDir != null) {
                    for (blob in all) {
                        val edge = maxOf(
                            kotlin.math.abs(blob.centroidX / w - 0.5),
                            kotlin.math.abs(blob.centroidY / h - 0.5),
                        ) * 2
                        if (edge < 0.85) continue
                        val pad = 12
                        val r = org.opencv.core.Rect(
                            (blob.boundingRect.x - pad).coerceAtLeast(0),
                            (blob.boundingRect.y - pad).coerceAtLeast(0),
                            0, 0,
                        )
                        r.width = (blob.boundingRect.width + 2 * pad).coerceAtMost(cell.cols() - r.x)
                        r.height = (blob.boundingRect.height + 2 * pad).coerceAtMost(cell.rows() - r.y)
                        val crop = Mat(cell, r)
                        org.opencv.imgcodecs.Imgcodecs.imwrite(
                            "$badgeDir/badge-${case.directory.name.take(8)}.png", crop,
                        )
                        crop.release()
                    }
                }
            } finally {
                cell.release()
            }
        }
    }

    /** Unused; keeps the HSV bounds visible next to the survey. */
    @Suppress("unused")
    private val bounds: Pair<Scalar, Scalar> =
        GreenDotDetector.DEFAULT_LOWER_HSV to GreenDotDetector.DEFAULT_UPPER_HSV

    /** TARGET cell, from annotated corners when present, else detection. */
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
