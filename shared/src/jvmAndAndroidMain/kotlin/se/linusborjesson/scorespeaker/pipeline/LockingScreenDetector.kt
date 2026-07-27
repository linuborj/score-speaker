package se.linusborjesson.scorespeaker.pipeline

import org.opencv.core.Mat
import se.linusborjesson.scorespeaker.Log
import se.linusborjesson.scorespeaker.processing.ContourCornerRefiner

/**
 * Lock-on wrapper around any [ScreenDetector] that exploits frame-to-frame
 * coherence to skip the expensive full detection on most frames.
 *
 * State machine:
 * ```
 *   COLD  (previousQuad == null)
 *     ↓ detect(): inner.detect(source)
 *     ↓ success  → store corners → WARM
 *     ↓ failure  → stay COLD
 *
 *   WARM  (previousQuad != null)
 *     ↓ detect(): ContourCornerRefiner.refineCorners(mat, previousCorners)
 *     ↓ refiner found new corners                → stay WARM (update corners)
 *     ↓ refiner returned the prior unchanged
 *     ↓   ↓ inner.detect(source) fallback
 *     ↓   ↓ success  → store corners → WARM
 *     ↓   ↓ failure  → clear corners → COLD
 * ```
 *
 * Why this is fast: full detection runs ORB on the whole frame (~100–500 ms on
 * a mid-range phone). Refinement is a 44-ray gradient scan from the previous
 * corners (a few ms). Within roughly ±15% of the previous edge-to-center distance
 * per frame the refiner tracks the display; outside that band it cleanly fails
 * (returns the prior unchanged) and we fall back to full detection.
 *
 * Not thread-safe — call from a single thread (e.g. the camera analyzer).
 */
class LockingScreenDetector(
    private val inner: ScreenDetector,
    private val refiner: ContourCornerRefiner = ContourCornerRefiner(),
) : ScreenDetector {

    private var previousQuad: Quadrilateral? = null

    /** True when the next [detect] will refine rather than run full detection. */
    val isLocked: Boolean get() = previousQuad != null

    override fun detect(source: ImageSource): DetectedScreen? {
        val prior = previousQuad
        if (prior != null) {
            val priorCorners = prior.toPairs()
            val refined = refiner.refineCorners(source.mat, priorCorners)
            if (refined !== priorCorners) {
                // The refiner returns the *exact* initialCorners reference when
                // its bezel scan finds nothing; any other return means it
                // located new corners (its own isValidQuadrilateral check
                // rejects degenerate shapes, so we don't re-validate here).
                val refinedQuad = Quadrilateral.fromPairs(refined)
                previousQuad = refinedQuad
                return DetectedScreen(
                    source = source,
                    screenQuad = refinedQuad,
                    confidence = 1.0f,
                    detectionMethod = "refined",
                )
            }
            Log.debug { "LockingScreenDetector: refiner could not lock; falling back to full detect" }
        }
        val full = inner.detect(source)
        previousQuad = full?.screenQuad
        return full
    }

    override fun loadTemplate(template: Mat) {
        inner.loadTemplate(template)
        // A new template usually means the calibration goal changed too —
        // dropping the lock forces a fresh full detect on the next frame.
        previousQuad = null
    }

    override fun templateSize(): Size2D? = inner.templateSize()

    /** Forget the lock. Next [detect] runs full detection. */
    fun reset() {
        previousQuad = null
    }
}
