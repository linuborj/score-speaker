package se.linusborjesson.scorespeaker.processing

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.pipeline.DetectedScreen
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.MatcherValidation
import se.linusborjesson.scorespeaker.pipeline.ScreenDetector
import se.linusborjesson.scorespeaker.pipeline.Size2D

/**
 * Holds pre-computed ORB descriptors for an input image, so multi-scale matching
 * can extract once and match many times.
 *
 * **Resource lifecycle**: holds two OpenCV Mats; call [close] when done.
 */
class ImageFeatures internal constructor(
    val width: Int,
    val height: Int,
    val keypoints: MatOfKeyPoint,
    val descriptors: Mat,
) : AutoCloseable {
    override fun close() {
        keypoints.release()
        descriptors.release()
    }
}

/**
 * ORB feature matcher: detect ORB keypoints + binary descriptors, brute-force
 * match with Hamming distance, fit a homography via RANSAC, refine corners via
 * [ContourCornerRefiner].
 *
 * Stateful: holds a single loaded template's features. Use [MultiScaleMatcher]
 * to manage several templates at different scales.
 *
 * @param maxFeatures Cap on ORB features per image.
 * @param minInliers Minimum RANSAC inliers for a successful match.
 * @param maxInputWidth Downscale inputs wider than this (0 = no limit). The
 *     returned corners are scaled back to original-image coordinates.
 */
class OrbTemplateMatcher(
    private val maxFeatures: Int = 500,
    private val minInliers: Int = 8,
    private val maxInputWidth: Int = 0,
) {
    init {
        CoordinateTransform.ensureOpenCv()
    }

    private val orb: ORB = ORB.create(maxFeatures)
    private val bfMatcher: BFMatcher = BFMatcher.create(Core.NORM_HAMMING, true)

    private var template: ImageFeatures? = null
    private var templateGray: Mat? = null

    /**
     * Load a template image. Magenta (255, 0, 255) pixels within `tolerance` are
     * masked out — used to ignore dynamic UI regions like scores and clocks.
     * Pass `tolerance < 0` to disable masking.
     */
    fun loadTemplate(color: Mat, magentaTolerance: Int = 30) {
        template?.close()
        templateGray?.release()

        val gray = Mat()
        Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)

        val mask = if (magentaTolerance >= 0) magentaMask(color, magentaTolerance) else Mat()
        try {
            val keypoints = MatOfKeyPoint()
            val descriptors = Mat()
            orb.detectAndCompute(gray, mask, keypoints, descriptors)

            template = ImageFeatures(color.cols(), color.rows(), keypoints, descriptors)
            templateGray = gray
        } finally {
            if (magentaTolerance >= 0) mask.release()
        }
    }

    /** Detect ORB features in an arbitrary image — useful for caching across scales. */
    fun detectFeatures(image: Mat): ImageFeatures {
        val gray = if (image.channels() > 1) {
            Mat().also { Imgproc.cvtColor(image, it, Imgproc.COLOR_BGR2GRAY) }
        } else {
            image  // borrow; don't release
        }
        try {
            val keypoints = MatOfKeyPoint()
            val descriptors = Mat()
            orb.detectAndCompute(gray, Mat(), keypoints, descriptors)
            return ImageFeatures(image.cols(), image.rows(), keypoints, descriptors)
        } finally {
            if (gray !== image) gray.release()
        }
    }

    /**
     * Match this matcher's template against the given input. Caller-supplied
     * features avoid re-detecting when the same input is matched against
     * multiple templates.
     */
    fun matchAgainst(input: ImageFeatures): MatchResult {
        val tpl = template ?: return MatchResult(false, null, 0, 0)
        if (tpl.descriptors.empty() || input.descriptors.empty()) {
            return MatchResult(false, null, 0, 0)
        }

        val matches = MatOfDMatch()
        bfMatcher.match(tpl.descriptors, input.descriptors, matches)
        val matchList = matches.toList()
        matches.release()
        if (matchList.size < 4) return MatchResult(false, null, matchList.size, 0)

        val sorted = matchList.sortedBy { it.distance }.take(MAX_MATCHES)
        val tplPts = tpl.keypoints.toList()
        val imgPts = input.keypoints.toList()
        val srcList = sorted.map { tplPts[it.queryIdx].pt }
        val dstList = sorted.map { imgPts[it.trainIdx].pt }

        val src = MatOfPoint2f(*srcList.toTypedArray())
        val dst = MatOfPoint2f(*dstList.toTypedArray())
        val inlierMask = Mat()
        try {
            // The homography Mat never leaves this method — the projected
            // corners are the only thing callers need, so it is released on
            // every path rather than exported.
            val homography = Calib3d.findHomography(src, dst, Calib3d.RANSAC, RANSAC_REPROJ_PX, inlierMask)
            if (homography.empty()) return MatchResult(false, null, sorted.size, 0)

            val inlierCount = (0 until inlierMask.rows()).count { inlierMask.get(it, 0)[0] > 0 }
            if (inlierCount < minInliers) {
                homography.release()
                return MatchResult(false, null, sorted.size, inlierCount)
            }

            val cornersMat = MatOfPoint2f()
            val tplCorners = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(tpl.width.toDouble(), 0.0),
                Point(tpl.width.toDouble(), tpl.height.toDouble()),
                Point(0.0, tpl.height.toDouble()),
            )
            try {
                Core.perspectiveTransform(tplCorners, cornersMat, homography)
                val rawCorners = cornersMat.toList().map { it.x to it.y }
                return MatchResult(
                    success = true,
                    corners = rawCorners,
                    matchCount = sorted.size,
                    inlierCount = inlierCount,
                    matchedPoints = srcList.zip(dstList),
                )
            } finally {
                homography.release()
                cornersMat.release()
                tplCorners.release()
            }
        } finally {
            src.release()
            dst.release()
            inlierMask.release()
        }
    }

    /**
     * Match the template against [image]. Downscales to [maxInputWidth] if set,
     * then scales corners back to the input image's coordinates.
     */
    fun match(image: Mat): MatchResult {
        if (maxInputWidth > 0 && image.cols() > maxInputWidth) {
            val scale = maxInputWidth.toDouble() / image.cols()
            val resized = Mat()
            try {
                Imgproc.resize(image, resized,
                    org.opencv.core.Size(maxInputWidth.toDouble(), image.rows() * scale))
                val features = detectFeatures(resized)
                val result = features.use { matchAgainst(it) }
                return if (result.corners != null) {
                    result.copy(corners = result.corners.map { (x, y) -> x / scale to y / scale })
                } else result
            } finally {
                resized.release()
            }
        }
        return detectFeatures(image).use { matchAgainst(it) }
    }

    /** Match the loaded template + refine corners via [ContourCornerRefiner]. */
    fun matchAndRefine(image: Mat): MatchResult {
        val raw = match(image)
        if (!raw.success || raw.corners == null) return raw
        val refined = ContourCornerRefiner().refineCorners(image, raw.corners, searchMargin = 0.15)
        return raw.copy(corners = refined)
    }

    /** The loaded template as a grayscale Mat, or null if no template loaded. */
    fun getTemplateMat(): Mat? = templateGray

    // -- ScreenDetector adapter --

    fun detect(source: ImageSource): DetectedScreen? {
        val result = matchAndRefine(source.mat)
        return MatcherValidation.toDetectedScreen(source, result, "OrbTemplateMatcher")
    }

    fun templateSize(): Size2D? = template?.let { Size2D(it.width, it.height) }

    private fun magentaMask(color: Mat, tolerance: Int): Mat {
        val low = Scalar(
            (255 - tolerance).coerceAtLeast(0).toDouble(),
            0.0,
            (255 - tolerance).coerceAtLeast(0).toDouble(),
        )
        val high = Scalar(255.0, tolerance.toDouble(), 255.0)
        val raw = Mat()
        val inverted = Mat()
        try {
            Core.inRange(color, low, high, raw)
            Core.bitwise_not(raw, inverted)
            return inverted
        } finally {
            raw.release()
        }
    }

    companion object {
        private const val MAX_MATCHES = 50
        private const val RANSAC_REPROJ_PX = 5.0
    }
}

/**
 * Result of a single template match. Deliberately Mat-free — carrying a
 * Mat here made every non-winning scale in [MultiScaleMatcher] (and the
 * winner, once consumed) a native-memory leak waiting for a release call
 * nobody owned.
 */
data class MatchResult(
    val success: Boolean,
    val corners: List<Pair<Double, Double>>?,
    val matchCount: Int,
    val inlierCount: Int,
    val matchedPoints: List<Pair<Point, Point>>? = null,
)
