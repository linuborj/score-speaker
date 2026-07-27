package se.linusborjesson.scorespeaker.processing

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.pipeline.DetectedScreen
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.MatcherValidation
import se.linusborjesson.scorespeaker.pipeline.ScreenDetector
import se.linusborjesson.scorespeaker.pipeline.Size2D

/**
 * Multi-scale template matcher. Holds one [OrbTemplateMatcher] per scale, with
 * the template pre-scaled and its features pre-computed. Per match call, the
 * source image's ORB features are computed once and reused across all scales —
 * this is the win over the original "detect features per scale" code.
 *
 * Steady-state caching: after a successful match, the winning scale is tried
 * first on the next call, then immediate neighbors, then full search.
 *
 * @param scales Template scales to try (1.0 == native template size).
 * @param maxFeatures Max ORB features per (template, source).
 * @param minInliers Min RANSAC inliers for success.
 * @param maxInputWidth Downscale source images wider than this for feature detection.
 *     0 disables. The returned corners are mapped back to original coordinates.
 */
class MultiScaleMatcher(
    private val scales: List<Double> = listOf(0.3, 0.5, 0.7, 1.0),
    private val maxFeatures: Int = 500,
    private val minInliers: Int = 8,
    private val maxInputWidth: Int = 0,
) : ScreenDetector {

    init {
        CoordinateTransform.ensureOpenCv()
    }

    private val matchers: MutableMap<Double, OrbTemplateMatcher> = LinkedHashMap()
    private var templateWidth: Int = 0
    private var templateHeight: Int = 0

    private var lastMatchedScale: Double? = null

    override fun loadTemplate(template: Mat) {
        loadTemplateWithColorKey(template)
    }

    /** Load template and (re)build per-scale matchers. Magenta pixels are masked. */
    fun loadTemplateWithColorKey(template: Mat, tolerance: Int = 30) {
        matchers.clear()
        lastMatchedScale = null
        templateWidth = template.cols()
        templateHeight = template.rows()

        for (scale in scales.sorted()) {
            val w = (templateWidth * scale).toInt().coerceAtLeast(1)
            val h = (templateHeight * scale).toInt().coerceAtLeast(1)
            val scaled = Mat()
            Imgproc.resize(template, scaled, Size(w.toDouble(), h.toDouble()))
            try {
                val matcher = OrbTemplateMatcher(maxFeatures, minInliers, maxInputWidth = 0)
                matcher.loadTemplate(scaled, magentaTolerance = tolerance)
                matchers[scale] = matcher
            } finally {
                scaled.release()
            }
        }
    }

    override fun detect(source: ImageSource): DetectedScreen? {
        val rawResult = matchRaw(source.mat)
        if (!rawResult.success || rawResult.corners == null) {
            return MatcherValidation.toDetectedScreen(source, rawResult, "MultiScaleMatcher")
        }
        // Refine corners using contour-based outside-in scan.
        val refined = ContourCornerRefiner().refineCorners(
            source.mat,
            rawResult.corners,
            searchMargin = 0.15,
        )
        return MatcherValidation.toDetectedScreen(
            source,
            rawResult.copy(corners = refined),
            "MultiScaleMatcher",
        )
    }

    override fun templateSize(): Size2D? = if (templateWidth > 0)
        Size2D(templateWidth, templateHeight) else null

    fun clearCache() { lastMatchedScale = null }

    /**
     * Match against [sourceMat]. Returns raw (unrefined) corners; caller refines.
     * Source features are detected once and reused across scales.
     */
    fun matchRaw(sourceMat: Mat): MatchResult {
        // Detect source features once. If maxInputWidth is set and sourceMat is
        // larger, work on a downscaled copy and scale corners back at the end.
        val (workMat, downscale) = if (maxInputWidth > 0 && sourceMat.cols() > maxInputWidth) {
            val scale = maxInputWidth.toDouble() / sourceMat.cols()
            val resized = Mat()
            Imgproc.resize(
                sourceMat, resized,
                Size(maxInputWidth.toDouble(), sourceMat.rows() * scale),
            )
            resized to scale
        } else {
            sourceMat to 1.0
        }
        val ownsWorkMat = workMat !== sourceMat

        val matcherForFeatures = matchers.values.firstOrNull()
            ?: return MatchResult(false, null, 0, 0)

        return matcherForFeatures.detectFeatures(workMat).use { features ->
            try {
                val tryOrder = orderedScales()
                val best = matchInOrder(tryOrder, features)
                rescaleCorners(best, downscale)
            } finally {
                if (ownsWorkMat) workMat.release()
            }
        }
    }

    private fun matchInOrder(order: List<Double>, features: ImageFeatures): MatchResult {
        var best: Pair<Double, MatchResult>? = null
        for (scale in order) {
            val matcher = matchers[scale] ?: continue
            val result = matcher.matchAgainst(features)
            // Lexicographic preference: prefer successful results; among those,
            // higher inlier count wins.
            val current = best
            if (current == null ||
                (result.success && !current.second.success) ||
                (result.success == current.second.success &&
                    result.inlierCount > current.second.inlierCount)
            ) {
                best = scale to result
            }
            // Early exit: if cached scale is succeeding strongly, no need to try others.
            if (scale == lastMatchedScale && result.success && result.inlierCount >= 2 * minInliers) {
                lastMatchedScale = scale
                return result
            }
        }
        val (winningScale, winningResult) = best ?: return MatchResult(false, null, 0, 0)
        if (winningResult.success) lastMatchedScale = winningScale
        return winningResult
    }

    private fun orderedScales(): List<Double> {
        val cached = lastMatchedScale ?: return scales.sortedDescending()
        // Cached first, then neighbors, then everything else (in descending order
        // so larger-scale templates with more detail are preferred on ties).
        val neighbors = neighborsOf(cached)
        val rest = scales.filter { it != cached && it !in neighbors }.sortedDescending()
        return listOf(cached) + neighbors + rest
    }

    private fun neighborsOf(scale: Double): List<Double> {
        val sorted = scales.sorted()
        val idx = sorted.indexOf(scale)
        if (idx < 0) return emptyList()
        return listOfNotNull(
            sorted.getOrNull(idx - 1),
            sorted.getOrNull(idx + 1),
        )
    }

    private fun rescaleCorners(result: MatchResult, downscale: Double): MatchResult {
        if (downscale == 1.0 || result.corners == null) return result
        val factor = 1.0 / downscale
        return result.copy(corners = result.corners.map { (x, y) -> x * factor to y * factor })
    }

}
