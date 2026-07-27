package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.processing.TargetGeometryEstimator
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic-image tests for [TargetGeometryEstimator]: a drawn aiming black
 * (with the ring lines and digit clutter the real display has) must yield
 * its centre and ring spacing; images without a plausible disc must yield
 * null so scoring falls back to constants.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TargetGeometryEstimatorTest {

    private val card = Scalar(200.0, 200.0, 200.0)
    private val black = Scalar(40.0, 40.0, 40.0)
    private val white = Scalar(230.0, 230.0, 230.0)

    @BeforeAll
    fun setup() {
        CoordinateTransform.ensureOpenCv()
    }

    /** 1000×800 light card with a disc of [radius] at ([cx], [cy]). */
    private fun target(cx: Double, cy: Double, radius: Int, clutter: Boolean): Mat {
        val mat = Mat(800, 1000, CvType.CV_8UC3, card)
        Imgproc.circle(mat, Point(cx, cy), radius, black, -1)
        if (clutter) {
            // Ring circles on the disc, ring numbers along the axes, and
            // thin dark rings outside the disc — the features that make
            // contour-based disc fitting fall apart.
            for (r in intArrayOf(30, 60, 90, 120, 150)) {
                Imgproc.circle(mat, Point(cx, cy), r, white, 2)
            }
            for (r in intArrayOf(210, 240)) {
                Imgproc.circle(mat, Point(cx, cy), r, black, 2)
            }
            for (i in 1..4) {
                Imgproc.putText(
                    mat, "$i", Point(cx - 8 + i, cy - 20.0 - i * 30),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, white, 2,
                )
                Imgproc.putText(
                    mat, "$i", Point(cx - 170.0 + i * 30, cy + 8),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, white, 2,
                )
            }
        }
        return mat
    }

    @Test
    fun `recovers centre and ring spacing of a clean disc`() {
        val mat = target(cx = 480.0, cy = 390.0, radius = 180, clutter = false)
        try {
            val geometry = assertNotNull(TargetGeometryEstimator().estimate(mat))
            assertTrue(abs(geometry.center.x - 480.0) < 6.0, "cx ${geometry.center.x}")
            assertTrue(abs(geometry.center.y - 390.0) < 6.0, "cy ${geometry.center.y}")
            val expectedSpacing = 180.0 / 6.1
            assertTrue(
                abs(geometry.ringSpacingPx - expectedSpacing) < expectedSpacing * 0.04,
                "spacing ${geometry.ringSpacingPx}, expected ≈$expectedSpacing",
            )
        } finally {
            mat.release()
        }
    }

    @Test
    fun `ring lines and digit clutter do not bias the estimate`() {
        val mat = target(cx = 480.0, cy = 390.0, radius = 180, clutter = true)
        try {
            val geometry = assertNotNull(TargetGeometryEstimator().estimate(mat))
            assertTrue(abs(geometry.center.x - 480.0) < 6.0, "cx ${geometry.center.x}")
            assertTrue(abs(geometry.center.y - 390.0) < 6.0, "cy ${geometry.center.y}")
            val expectedSpacing = 180.0 / 6.1
            assertTrue(
                abs(geometry.ringSpacingPx - expectedSpacing) < expectedSpacing * 0.04,
                "spacing ${geometry.ringSpacingPx}, expected ≈$expectedSpacing",
            )
        } finally {
            mat.release()
        }
    }

    @Test
    fun `blank canvas yields null`() {
        val mat = Mat(800, 1000, CvType.CV_8UC3, card)
        try {
            assertNull(TargetGeometryEstimator().estimate(mat))
        } finally {
            mat.release()
        }
    }

    @Test
    fun `uniformly dark canvas yields null`() {
        val mat = Mat(800, 1000, CvType.CV_8UC3, black)
        try {
            assertNull(TargetGeometryEstimator().estimate(mat))
        } finally {
            mat.release()
        }
    }

    @Test
    fun `disc clipped by the image border yields null`() {
        // Centre near the left edge so the disc runs out of frame — its
        // component touches the border and is discarded.
        val mat = target(cx = 60.0, cy = 390.0, radius = 180, clutter = false)
        try {
            assertNull(TargetGeometryEstimator().estimate(mat))
        } finally {
            mat.release()
        }
    }

    @Test
    fun `implausibly small dark blob yields null`() {
        val mat = Mat(800, 1000, CvType.CV_8UC3, card)
        try {
            Imgproc.circle(mat, Point(480.0, 390.0), 30, black, -1)
            assertNull(TargetGeometryEstimator().estimate(mat))
        } finally {
            mat.release()
        }
    }
}
