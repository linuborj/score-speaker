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
import se.linusborjesson.scorespeaker.pipeline.DetectedScreen
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.LockingScreenDetector
import se.linusborjesson.scorespeaker.pipeline.Point2D
import se.linusborjesson.scorespeaker.pipeline.Quadrilateral
import se.linusborjesson.scorespeaker.pipeline.ScreenDetector
import se.linusborjesson.scorespeaker.pipeline.Size2D
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the cold/warm/back-to-cold state machine of [LockingScreenDetector].
 * Uses a deterministic fake [ScreenDetector] and synthetic source frames so the
 * refiner's behaviour is predictable: a bright rectangle on a black background
 * is exactly the dark-bezel-then-bright-content pattern its gradient scan
 * expects.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockingScreenDetectorTest {

    @BeforeAll
    fun setup() {
        CoordinateTransform.ensureOpenCv()
    }

    @Test
    fun `cold state runs full detection and stores its corners`() {
        val inner = ScriptedDetector(listOf(detectedAt(quad(200, 200, 600, 600))))
        val locking = LockingScreenDetector(inner)

        bezelImage().useMat { mat ->
            ImageSource(mat.clone()).use { src ->
                val result = locking.detect(src)
                assertNotNull(result)
                assertEquals(1, inner.calls, "first call should hit the inner detector")
                assertTrue(locking.isLocked, "successful detect should lock on")
            }
        }
    }

    @Test
    fun `warm state refines without calling the inner detector`() {
        // Slightly-off prior corners — within the refiner's tolerance band so
        // the bezel scan finds the true rectangle edges.
        val priorCorners = quad(220, 220, 580, 580)
        val inner = ScriptedDetector(listOf(detectedAt(priorCorners)))
        val locking = LockingScreenDetector(inner)

        bezelImage().useMat { mat ->
            // First call: cold → inner gets called → state advances to warm.
            ImageSource(mat.clone()).use { src ->
                locking.detect(src)
            }
            assertEquals(1, inner.calls)

            // Second call on a similar frame: warm → refiner runs → inner is
            // NOT called.
            ImageSource(mat.clone()).use { src ->
                val refined = locking.detect(src)
                assertNotNull(refined)
                assertEquals("refined", refined.detectionMethod)
                assertEquals(1, inner.calls, "warm-frame call must skip the inner detector")
                assertTrue(locking.isLocked)
            }
        }
    }

    @Test
    fun `refiner failure falls back to inner detector`() {
        // The prior is reasonable for the first frame, but the second frame is
        // a uniform field with no bezel pattern → refiner returns the prior
        // unchanged → falls through to the inner detector.
        val inner = ScriptedDetector(
            listOf(
                detectedAt(quad(220, 220, 580, 580)),
                detectedAt(quad(220, 220, 580, 580)),
            ),
        )
        val locking = LockingScreenDetector(inner)

        bezelImage().useMat { warmMat ->
            ImageSource(warmMat.clone()).use { src -> locking.detect(src) }
        }
        assertEquals(1, inner.calls)

        uniformImage().useMat { coldMat ->
            ImageSource(coldMat.clone()).use { src ->
                locking.detect(src)
            }
        }
        assertEquals(2, inner.calls, "refiner failure should cause an inner.detect fallback")
    }

    @Test
    fun `full-detect failure resets to cold`() {
        val inner = ScriptedDetector(
            listOf(
                detectedAt(quad(220, 220, 580, 580)),  // first call: succeed → warm
                null,                                   // refiner fallback: fail → cold
            ),
        )
        val locking = LockingScreenDetector(inner)

        bezelImage().useMat { mat ->
            ImageSource(mat.clone()).use { src -> locking.detect(src) }
        }
        assertTrue(locking.isLocked)

        uniformImage().useMat { mat ->
            ImageSource(mat.clone()).use { src ->
                val result = locking.detect(src)
                assertNull(result)
            }
        }
        assertFalse(locking.isLocked, "failed fallback detect should reset to cold")
    }

    @Test
    fun `reset clears the lock`() {
        val inner = ScriptedDetector(listOf(detectedAt(quad(200, 200, 600, 600))))
        val locking = LockingScreenDetector(inner)
        bezelImage().useMat { mat ->
            ImageSource(mat.clone()).use { src -> locking.detect(src) }
        }
        assertTrue(locking.isLocked)
        locking.reset()
        assertFalse(locking.isLocked)
    }

    // --- helpers ---

    /** 800×800 black background with a bright rectangle at (200..600, 200..600). */
    private fun bezelImage(): Mat {
        val mat = Mat(800, 800, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        Imgproc.rectangle(
            mat,
            Point(200.0, 200.0),
            Point(600.0, 600.0),
            Scalar(255.0, 255.0, 255.0),
            -1,
        )
        return mat
    }

    /** 800×800 uniform mid-grey — no bezel transition for the refiner to find. */
    private fun uniformImage(): Mat =
        Mat(800, 800, CvType.CV_8UC3, Scalar(128.0, 128.0, 128.0))

    private fun quad(x1: Int, y1: Int, x2: Int, y2: Int): Quadrilateral =
        Quadrilateral(
            topLeft = Point2D(x1.toDouble(), y1.toDouble()),
            topRight = Point2D(x2.toDouble(), y1.toDouble()),
            bottomRight = Point2D(x2.toDouble(), y2.toDouble()),
            bottomLeft = Point2D(x1.toDouble(), y2.toDouble()),
        )

    private fun detectedAt(q: Quadrilateral): DetectedScreen {
        // Note: source is set when ScriptedDetector returns the value.
        return DetectedScreen(
            source = SOURCE_PLACEHOLDER,
            screenQuad = q,
            confidence = 0.9f,
            detectionMethod = "fake",
        )
    }

    /** Scripted in-place detector that hands out canned results in order. */
    private class ScriptedDetector(private val results: List<DetectedScreen?>) : ScreenDetector {
        var calls = 0
            private set

        override fun detect(source: ImageSource): DetectedScreen? {
            val r = results.getOrNull(calls)
            calls++
            return r?.copy(source = source)
        }

        override fun loadTemplate(template: Mat) {}
        override fun templateSize(): Size2D? = null
    }

    /** Placeholder Mat — overwritten by ScriptedDetector before it leaves the test. */
    // `by lazy` because OpenCV's native lib isn't loaded until @BeforeAll runs;
    // touching Mat() in a field initialiser throws UnsatisfiedLinkError.
    private val SOURCE_PLACEHOLDER by lazy {
        ImageSource(Mat(1, 1, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)))
    }

    private inline fun <T> Mat.useMat(block: (Mat) -> T): T = try {
        block(this)
    } finally {
        release()
    }
}
