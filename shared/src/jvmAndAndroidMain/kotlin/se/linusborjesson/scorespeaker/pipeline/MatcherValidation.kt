package se.linusborjesson.scorespeaker.pipeline

import se.linusborjesson.scorespeaker.processing.MatchResult

/**
 * Shared validation and scoring utilities for screen detection.
 */
object MatcherValidation {

    /**
     * Calculate confidence score from match result.
     * Combines inlier count (absolute quality) with inlier ratio (geometric consistency).
     */
    fun calculateConfidence(result: MatchResult): Float {
        return calculateConfidence(result.inlierCount, result.matchCount)
    }

    /**
     * Calculate confidence score from inlier and match counts.
     */
    fun calculateConfidence(inlierCount: Int, matchCount: Int): Float {
        // Base confidence from inlier count (50+ inliers = very confident)
        val inlierScore = (inlierCount / 50f).coerceIn(0f, 1f)

        // Inlier ratio (geometric consistency)
        val inlierRatio = if (matchCount > 0) {
            inlierCount.toFloat() / matchCount
        } else 0f

        return (inlierScore * 0.6f + inlierRatio * 0.4f).coerceIn(0f, 1f)
    }

    /**
     * Validate that detected corners are reasonable.
     */
    fun validateCorners(
        corners: List<Pair<Double, Double>>,
        imgWidth: Int,
        imgHeight: Int
    ): Boolean {
        if (corners.size != 4) return false

        // Allow some margin outside image bounds (perspective can extend slightly)
        val margin = minOf(imgWidth, imgHeight) * 0.1

        for ((x, y) in corners) {
            if (x < -margin || x > imgWidth + margin ||
                y < -margin || y > imgHeight + margin) {
                return false
            }
        }

        // Check area is reasonable (1% to 150% of image)
        val area = calculateQuadArea(corners)
        val imgArea = imgWidth.toDouble() * imgHeight
        if (area < imgArea * 0.01 || area > imgArea * 1.5) {
            return false
        }

        return isConvexQuadrilateral(corners)
    }

    /**
     * Calculate area of quadrilateral using Shoelace formula.
     */
    fun calculateQuadArea(corners: List<Pair<Double, Double>>): Double {
        var area = 0.0
        for (i in corners.indices) {
            val j = (i + 1) % corners.size
            area += corners[i].first * corners[j].second
            area -= corners[j].first * corners[i].second
        }
        return kotlin.math.abs(area) / 2.0
    }

    /**
     * Check if corners form a convex quadrilateral (no self-intersection).
     */
    fun isConvexQuadrilateral(corners: List<Pair<Double, Double>>): Boolean {
        if (corners.size != 4) return false

        var lastSign: Int? = null
        for (i in corners.indices) {
            val p1 = corners[i]
            val p2 = corners[(i + 1) % 4]
            val p3 = corners[(i + 2) % 4]

            val cross = (p2.first - p1.first) * (p3.second - p2.second) -
                        (p2.second - p1.second) * (p3.first - p2.first)
            val sign = if (cross > 0) 1 else if (cross < 0) -1 else 0

            if (sign != 0) {
                if (lastSign != null && sign != lastSign) return false
                lastSign = sign
            }
        }
        return true
    }

    /**
     * Create DetectedScreen from match result if valid.
     */
    fun toDetectedScreen(
        source: ImageSource,
        result: MatchResult,
        detectionMethod: String
    ): DetectedScreen? {
        if (!result.success || result.corners == null) return null

        val corners = result.corners
        if (!validateCorners(corners, source.width, source.height)) return null

        val quad = Quadrilateral.fromPairs(corners)
        val confidence = calculateConfidence(result)

        return DetectedScreen(
            source = source,
            screenQuad = quad,
            confidence = confidence,
            detectionMethod = detectionMethod
        )
    }
}
