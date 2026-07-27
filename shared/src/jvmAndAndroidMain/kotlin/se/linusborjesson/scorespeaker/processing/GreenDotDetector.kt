package se.linusborjesson.scorespeaker.processing

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import kotlin.math.PI

/**
 * Locates the SIUS "latest shot" marker — a solid green disk with the shot
 * number rendered in white on top — in a BGR [Mat] that has already been
 * cropped to the target rings area.
 *
 * Algorithm exploits two pieces of domain knowledge:
 *
 * 1. The dot's radius is roughly constant as a fraction of the target-cell
 *    dimensions. Components whose area falls outside the expected band
 *    (computed from [minRadiusRatio] / [maxRadiusRatio]) are excluded.
 * 2. There is exactly one dot per per-shot screen. If two or more
 *    components pass the size filter, *something is wrong* — calibration
 *    drift, multiple displays in frame, sensor glitch. Better to refuse
 *    (return null) than average them.
 *
 * Use [candidates] to see every in-band component (useful for the labeling
 * app's diagnostic overlay); use [detect] for the production "give me the
 * dot, or null" answer.
 *
 * Pipeline:
 *   1. BGR → HSV.
 *   2. `inRange` for the green band → binary mask.
 *   3. Morphological close so white-text holes inside the dot get bridged;
 *    safe because we've cropped to TARGET so there are no other greens
 *    to merge with.
 *   4. `connectedComponentsWithStats` enumerates blobs with area + bbox +
 *      centroid.
 *   5. Filter to those whose pixel-count lies between
 *      `π·(minRadiusRatio·W)²` and `π·(maxRadiusRatio·W)²`, where `W` is
 *      the input Mat's width.
 *   6. [detect]: return the sole in-band candidate, else null.
 */
class GreenDotDetector(
    private val lowerHsv: Scalar = DEFAULT_LOWER_HSV,
    private val upperHsv: Scalar = DEFAULT_UPPER_HSV,
    /** Lower bound on the dot radius, as a fraction of the input Mat's width. */
    private val minRadiusRatio: Double = 0.020,
    /** Upper bound on the dot radius, as a fraction of the input Mat's width. */
    private val maxRadiusRatio: Double = 0.060,
    private val closeKernelSize: Int = 5,
) {
    companion object {
        /** Default HSV lower bound for the SIUS green band. Exposed so tools
         *  that need to visualise the same threshold (e.g. a mask overlay in
         *  the labeling app) use identical bounds. */
        val DEFAULT_LOWER_HSV: Scalar = Scalar(40.0, 100.0, 50.0)
        /** Default HSV upper bound — see [DEFAULT_LOWER_HSV]. */
        val DEFAULT_UPPER_HSV: Scalar = Scalar(85.0, 255.0, 255.0)
    }

    init {
        CoordinateTransform.ensureOpenCv()
    }

    /**
     * All connected components whose pixel-count lies in the expected band.
     * Empty list when nothing fits. Centroids and bboxes are in the input
     * Mat's pixel coordinates.
     */
    fun candidates(bgr: Mat): List<GreenDotDetection> {
        if (bgr.empty()) return emptyList()

        val w = bgr.cols()
        val minArea = PI * (minRadiusRatio * w) * (minRadiusRatio * w)
        val maxArea = PI * (maxRadiusRatio * w) * (maxRadiusRatio * w)

        val hsv = Mat()
        val mask = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            Core.inRange(hsv, lowerHsv, upperHsv, mask)

            if (closeKernelSize > 1) {
                val kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    Size(closeKernelSize.toDouble(), closeKernelSize.toDouble()),
                )
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()
            }

            val componentCount = Imgproc.connectedComponentsWithStats(
                mask, labels, stats, centroids,
            )
            val out = mutableListOf<GreenDotDetection>()
            // Component 0 is background; skip it.
            for (i in 1 until componentCount) {
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                if (area < minArea || area > maxArea) continue
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val rectW = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val rectH = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val cx = centroids.get(i, 0)[0]
                val cy = centroids.get(i, 1)[0]
                out += GreenDotDetection(
                    centroidX = cx,
                    centroidY = cy,
                    boundingRect = Rect(x, y, rectW, rectH),
                    pixelCount = area.toInt(),
                )
            }
            return out
        } finally {
            hsv.release()
            mask.release()
            labels.release()
            stats.release()
            centroids.release()
        }
    }

    /**
     * The single in-band candidate, or null when there are zero or many.
     * The "many" case is a signal that something upstream is wrong — caller
     * should treat it as "no detection this frame" and the change-tracker /
     * resilient-state-tracking layer keeps the previous value.
     */
    fun detect(bgr: Mat): GreenDotDetection? = candidates(bgr).singleOrNull()
}

/**
 * A single green-blob detection. Coordinates are in the input Mat's pixel
 * space — caller is responsible for any further coordinate transformation.
 */
data class GreenDotDetection(
    val centroidX: Double,
    val centroidY: Double,
    val boundingRect: Rect,
    val pixelCount: Int,
)
