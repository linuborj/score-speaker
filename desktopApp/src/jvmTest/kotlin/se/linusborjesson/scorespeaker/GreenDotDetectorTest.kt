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
import se.linusborjesson.scorespeaker.processing.GreenDotDetector
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic-image tests for [GreenDotDetector]. The detector assumes a
 * pre-cropped TARGET cell as input; each test draws onto a 1000 px wide
 * canvas so the size-band gates (relative to width) admit dots in the
 * ~20–60 px radius range — matching the SIUS dot's size in the rectified
 * test corpus.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GreenDotDetectorTest {

    private val sius = Scalar(80.0, 200.0, 80.0)        // BGR — SIUS-ish green
    private val bg = Scalar(20.0, 20.0, 20.0)            // dark background
    private val white = Scalar(255.0, 255.0, 255.0)

    @BeforeAll
    fun setup() {
        CoordinateTransform.ensureOpenCv()
    }

    private fun canvas(): Mat = Mat(1000, 1000, CvType.CV_8UC3, bg)

    @Test
    fun `single in-band dot is detected and located correctly`() {
        val mat = canvas()
        try {
            Imgproc.circle(mat, Point(500.0, 500.0), 35, sius, -1)
            val hit = GreenDotDetector().detect(mat)
            assertNotNull(hit)
            assertCentroidNear(hit.centroidX, hit.centroidY, 500.0, 500.0)
        } finally {
            mat.release()
        }
    }

    @Test
    fun `green disk with a white text hole still centres on the disk`() {
        val mat = canvas()
        try {
            val cx = 500.0
            val cy = 500.0
            Imgproc.circle(mat, Point(cx, cy), 35, sius, -1)
            Imgproc.rectangle(
                mat,
                Point(cx - 5.0, cy - 20.0),
                Point(cx + 5.0, cy + 20.0),
                white,
                -1,
            )
            val hit = GreenDotDetector().detect(mat)
            assertNotNull(hit, "should still detect even with a text hole punching the green")
            assertCentroidNear(hit.centroidX, hit.centroidY, cx, cy, tolerance = 2.5)
        } finally {
            mat.release()
        }
    }

    @Test
    fun `two in-band blobs return null (singularity)`() {
        val mat = canvas()
        try {
            Imgproc.circle(mat, Point(300.0, 300.0), 35, sius, -1)
            Imgproc.circle(mat, Point(700.0, 700.0), 35, sius, -1)
            assertNull(
                GreenDotDetector().detect(mat),
                "two in-band candidates → refuse rather than guess",
            )
        } finally {
            mat.release()
        }
    }

    @Test
    fun `one in-band plus one too-small blob picks the in-band`() {
        val mat = canvas()
        try {
            Imgproc.circle(mat, Point(500.0, 500.0), 35, sius, -1)
            // 5 px speck — radius/width = 0.005, below 0.020 lower band.
            Imgproc.circle(mat, Point(100.0, 100.0), 5, sius, -1)
            val hit = GreenDotDetector().detect(mat)
            assertNotNull(hit)
            assertCentroidNear(hit.centroidX, hit.centroidY, 500.0, 500.0)
        } finally {
            mat.release()
        }
    }

    @Test
    fun `one in-band plus one too-large blob picks the in-band`() {
        val mat = canvas()
        try {
            Imgproc.circle(mat, Point(500.0, 500.0), 35, sius, -1)
            // Big green region — radius/width = 0.15, way above 0.060 upper band.
            Imgproc.circle(mat, Point(150.0, 850.0), 150, sius, -1)
            val hit = GreenDotDetector().detect(mat)
            assertNotNull(hit)
            assertCentroidNear(hit.centroidX, hit.centroidY, 500.0, 500.0)
        } finally {
            mat.release()
        }
    }

    @Test
    fun `zero in-band candidates returns null and empty list`() {
        val mat = canvas()
        try {
            // Only specks below the band; nothing in-band.
            Imgproc.circle(mat, Point(200.0, 200.0), 4, sius, -1)
            Imgproc.circle(mat, Point(800.0, 800.0), 4, sius, -1)
            val det = GreenDotDetector()
            assertNull(det.detect(mat))
            assertTrue(det.candidates(mat).isEmpty())
        } finally {
            mat.release()
        }
    }

    @Test
    fun `candidates() returns all in-band components without applying singularity`() {
        val mat = canvas()
        try {
            Imgproc.circle(mat, Point(300.0, 300.0), 35, sius, -1)
            Imgproc.circle(mat, Point(700.0, 700.0), 35, sius, -1)
            val candidates = GreenDotDetector().candidates(mat)
            assertEquals(2, candidates.size, "both in-band components should be returned")
            // Order isn't guaranteed; check both centroids are present.
            assertTrue(candidates.any { abs(it.centroidX - 300) < 2 && abs(it.centroidY - 300) < 2 })
            assertTrue(candidates.any { abs(it.centroidX - 700) < 2 && abs(it.centroidY - 700) < 2 })
        } finally {
            mat.release()
        }
    }

    @Test
    fun `tolerates thin ring lines crossing the dot`() {
        val mat = canvas()
        try {
            val cx = 500.0
            val cy = 500.0
            Imgproc.circle(mat, Point(cx, cy), 35, sius, -1)
            Imgproc.line(mat, Point(cx - 50.0, cy), Point(cx + 50.0, cy), Scalar(0.0, 0.0, 0.0), 2)
            val hit = GreenDotDetector().detect(mat)
            assertNotNull(hit, "morphological close should bridge the ring-line gap into one component")
            assertCentroidNear(hit.centroidX, hit.centroidY, cx, cy, tolerance = 3.0)
        } finally {
            mat.release()
        }
    }

    private fun assertCentroidNear(
        actualX: Double,
        actualY: Double,
        expectedX: Double,
        expectedY: Double,
        tolerance: Double = 1.5,
    ) {
        assertTrue(
            abs(actualX - expectedX) < tolerance && abs(actualY - expectedY) < tolerance,
            "centroid ($actualX, $actualY) not within $tolerance of expected ($expectedX, $expectedY)",
        )
    }
}
