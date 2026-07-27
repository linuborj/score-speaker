package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.processing.ContourCornerRefiner
import kotlin.test.assertEquals

/**
 * Regression tests for [ContourCornerRefiner] focused on bad inputs from
 * ORB. The Android crash we hit was: ORB returned corners entirely outside
 * the source frame on a misdetection, `computeSearchRegion` produced a
 * rect with negative width, `Mat(mat, rect)` collapsed to empty, and the
 * next `cvtColor` blew up with `!_src.empty()`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContourCornerRefinerTest {

    @BeforeAll
    fun setup() {
        CoordinateTransform.ensureOpenCv()
    }

    @Test
    fun `corners entirely outside the frame collapse search region — refinement falls back to initial corners`() {
        val mat = Mat(1080, 1920, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        try {
            // All four corners far beyond the right edge of the image.
            val badCorners = listOf(
                3000.0 to 100.0,
                3500.0 to 100.0,
                3500.0 to 800.0,
                3000.0 to 800.0,
            )
            val refined = ContourCornerRefiner().refineCorners(mat, badCorners)
            assertEquals(badCorners, refined,
                "no refinement possible — should return the initial corners unchanged")
        } finally {
            mat.release()
        }
    }

    @Test
    fun `corners with negative coordinates do not crash`() {
        val mat = Mat(1080, 1920, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        try {
            val badCorners = listOf(
                -500.0 to -500.0,
                -100.0 to -500.0,
                -100.0 to -100.0,
                -500.0 to -100.0,
            )
            val refined = ContourCornerRefiner().refineCorners(mat, badCorners)
            // We don't care about the exact return value; the contract is
            // "doesn't throw."
            assertEquals(4, refined.size)
        } finally {
            mat.release()
        }
    }
}
