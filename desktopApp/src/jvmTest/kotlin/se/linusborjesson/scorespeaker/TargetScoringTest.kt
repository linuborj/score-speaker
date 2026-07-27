package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.pipeline.Point2D
import se.linusborjesson.scorespeaker.processing.TargetScoring
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests pin the math, not the calibration. The default ringSpacingRatio
 * is for real-corpus use; here we pick spacing that gives clean integer
 * pixel-per-ring numbers so the assertions are obvious.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TargetScoringTest {

    @BeforeAll
    fun setup() {
        CoordinateTransform.ensureOpenCv()
    }

    /** 1000×1000 cell, ringSpacingRatio = 0.05 → 50 px per ring. */
    private fun scoring() = TargetScoring(
        ringSpacingRatio = 0.05,
    )
    private fun cellMat(): Mat = Mat(1000, 1000, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))

    @Test
    fun `dot at centre scores 10 9 and zero offset`() {
        val mat = cellMat()
        try {
            val m = scoring().measure(mat, Point2D(500.0, 500.0))
            assertNotNull(m)
            assertNear(m.score, 10.9)
            assertNear(m.offsetXRings, 0.0)
            assertNear(m.offsetYRings, 0.0)
            assertNear(m.distanceRings, 0.0)
        } finally {
            mat.release()
        }
    }

    @Test
    fun `dot 100 px right and 50 px down → offset 2 0 right, 1 0 down`() {
        // 50 px per ring → 100 px = 2 rings, 50 px = 1 ring.
        val mat = cellMat()
        try {
            val m = scoring().measure(mat, Point2D(600.0, 550.0))
            assertNotNull(m)
            assertNear(m.offsetXRings, 2.0)
            assertNear(m.offsetYRings, 1.0)
            assertNear(m.distanceRings, kotlin.math.sqrt(5.0))
            assertNear(m.score, 10.9 - kotlin.math.sqrt(5.0))
        } finally {
            mat.release()
        }
    }

    @Test
    fun `dot left and above centre yields negative offsets`() {
        val mat = cellMat()
        try {
            val m = scoring().measure(mat, Point2D(400.0, 450.0))
            assertNotNull(m)
            assertNear(m.offsetXRings, -2.0)
            assertNear(m.offsetYRings, -1.0)
        } finally {
            mat.release()
        }
    }

    @Test
    fun `score is clamped at zero for very distant dots`() {
        // Distance way beyond 10.9 rings → raw score is negative.
        val mat = cellMat()
        try {
            val m = scoring().measure(mat, Point2D(500.0 + 50 * 15.0, 500.0))
            assertNotNull(m)
            assertNear(m.score, 0.0)
        } finally {
            mat.release()
        }
    }

    @Test
    fun `null result when the centre finder cannot locate the bullseye`() {
        // Centre finder returns null on empty Mat → scoring also null.
        val emptyMat = Mat()
        assertNull(scoring().measure(emptyMat, Point2D(0.0, 0.0)))
    }

    private fun assertNear(actual: Double, expected: Double, eps: Double = 0.01) {
        assertTrue(
            abs(actual - expected) < eps,
            "expected ~$expected, got $actual (eps=$eps)",
        )
    }
}
