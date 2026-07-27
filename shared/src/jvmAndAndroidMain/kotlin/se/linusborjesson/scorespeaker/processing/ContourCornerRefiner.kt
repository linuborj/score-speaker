package se.linusborjesson.scorespeaker.processing

import se.linusborjesson.scorespeaker.Log

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Refines corner positions using outside-in gradient detection.
 *
 * Strategy:
 * 1. Use ORB corners as initial estimate
 * 2. Shoot rays from OUTSIDE the display inward, perpendicular to each
 *    estimated edge (11 rays per edge)
 * 3. Find where the dark bezel transitions to bright display content.
 *    Thresholds are adaptive per ray (hysteresis levels derived from the
 *    ray's own intensity range), so the scan keeps working under dim or
 *    bright exposure. The transition is localized to sub-pixel precision
 *    at the maximum-gradient position.
 * 4. RANSAC-fit lines through the detected edge points (rejects rays that
 *    latched onto interior content or glare) and compute corner
 *    intersections from the consensus lines.
 *
 * This approach works because the black plastic bezel is always present,
 * providing a reliable dark→bright transition at the display edge.
 */
class ContourCornerRefiner {

    private companion object {
        /** Rays per edge, spread across [RAY_SPAN_START, RAY_SPAN_END] of the edge. */
        const val RAYS_PER_EDGE = 11
        const val RAY_SPAN_START = 0.08
        const val RAY_SPAN_END = 0.92

        /** Minimum intensity range along a ray for the transition to be trusted. */
        const val MIN_RAY_CONTRAST = 40.0

        /**
         * Hysteresis levels as fractions of the ray's intensity range. The
         * bright level is deliberately low: the display's outer frame line is
         * often much dimmer than interior content (title bars, white cells),
         * and a high level would skip past it to the interior. 0.2/0.4 of the
         * range correspond to absolute thresholds of ~80/120 at normal
         * exposure while staying meaningful under dim or bright exposure.
         */
        const val DARK_LEVEL_FRACTION = 0.2
        const val BRIGHT_LEVEL_FRACTION = 0.4

        /** RANSAC: max perpendicular distance (px) for a point to count as inlier. */
        const val RANSAC_INLIER_TOLERANCE = 2.5

        /** Minimum consensus points per edge line. */
        const val MIN_EDGE_INLIERS = 3

        /**
         * A consensus line qualifies for the outermost-line preference when
         * its inlier count is at least this fraction of the best count.
         */
        const val CONSENSUS_QUALIFY_FRACTION = 0.7
    }

    /**
     * Refine corners by finding the display border contour.
     *
     * @param mat The source image
     * @param initialCorners Initial corner estimates from ORB matching (TL, TR, BR, BL order)
     * @param searchMargin Margin (as ratio) to expand search region beyond initial corners (default 0.05 = 5%)
     * @return Refined corner positions, or [initialCorners] (same reference) when refinement fails
     */
    fun refineCorners(
        mat: Mat,
        initialCorners: List<Pair<Double, Double>>,
        searchMargin: Double = 0.05
    ): List<Pair<Double, Double>> {
        require(initialCorners.size == 4) { "Must have 4 corners" }

        // Define search region based on initial corners
        val searchRegion = computeSearchRegion(initialCorners, mat.cols(), mat.rows(), searchMargin)

        // If ORB returned corners that fall entirely outside the source frame
        // (degenerate homography), the clamped search region collapses to
        // zero area and `Mat(mat, rect)` produces an empty Mat that would
        // crash the next cvtColor. Skip refinement and let downstream
        // validation reject the match.
        if (searchRegion.width <= 0 || searchRegion.height <= 0) {
            Log.debug { "ContourCornerRefiner: search region has zero area ($searchRegion); skipping refinement" }
            return initialCorners
        }

        // Extract ROI for processing
        val roi = Mat(mat, searchRegion)

        // Find contours in the search region
        val contour = findDisplayContour(roi, initialCorners, searchRegion)

        roi.release()

        return if (contour != null) {
            Log.debug { "ContourCornerRefiner: Successfully found display border contour" }
            contour
        } else {
            Log.debug { "ContourCornerRefiner: No suitable contour found, keeping initial corners" }
            initialCorners
        }
    }

    /**
     * Compute a search region that encompasses the initial corners with margin.
     */
    private fun computeSearchRegion(
        corners: List<Pair<Double, Double>>,
        imgWidth: Int,
        imgHeight: Int,
        margin: Double
    ): Rect {
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }

        val minX = xs.minOrNull()!!
        val maxX = xs.maxOrNull()!!
        val minY = ys.minOrNull()!!
        val maxY = ys.maxOrNull()!!

        val width = maxX - minX
        val height = maxY - minY

        // Expand by margin
        val expandX = width * margin
        val expandY = height * margin

        // Clamp x/y to a valid range. The previous code only enforced a lower
        // bound of 0; if ORB returned corners outside the source frame
        // (minX > imgWidth), x exceeded imgWidth and the subsequent
        // `coerceAtMost(imgWidth - x)` produced a negative width — which
        // collapses the ROI Mat to empty and crashes cvtColor downstream.
        val maxValidX = (imgWidth - 1).coerceAtLeast(0)
        val maxValidY = (imgHeight - 1).coerceAtLeast(0)
        val x = (minX - expandX).toInt().coerceIn(0, maxValidX)
        val y = (minY - expandY).toInt().coerceIn(0, maxValidY)
        val w = (width + 2 * expandX).toInt().coerceAtMost(imgWidth - x).coerceAtLeast(0)
        val h = (height + 2 * expandY).toInt().coerceAtMost(imgHeight - y).coerceAtLeast(0)

        Log.debug { "ContourCornerRefiner: Search region = ($x, $y, $w x $h)" }
        return Rect(x, y, w, h)
    }

    /**
     * Find the display border using gradient analysis.
     * Looks for the signature pattern: dark LCD content → white border → black bezel
     * This is immune to bright backgrounds since they don't have this pattern.
     */
    private fun findDisplayContour(
        roi: Mat,
        initialCorners: List<Pair<Double, Double>>,
        searchRegion: Rect
    ): List<Pair<Double, Double>>? {
        // Try gradient-based edge detection (most robust)
        val gradientResult = findByGradientAnalysis(roi, initialCorners, searchRegion)
        if (gradientResult != null) {
            Log.debug { "ContourCornerRefiner: Using gradient-based corners" }
            return gradientResult
        }

        // Fall back to contour detection
        val contourResult = findByWhiteContours(roi, initialCorners, searchRegion)
        if (contourResult != null) {
            Log.debug { "ContourCornerRefiner: Using contour-based corners" }
            return contourResult
        }

        Log.debug { "ContourCornerRefiner: All methods failed, keeping initial corners" }
        return null
    }

    /**
     * Find border edges using gradient analysis.
     * Scans from outside the display inward to find the bezel→bright transition
     * on each edge, then RANSAC-fits an edge line through the detected points.
     */
    private fun findByGradientAnalysis(
        roi: Mat,
        initialCorners: List<Pair<Double, Double>>,
        searchRegion: Rect
    ): List<Pair<Double, Double>>? {
        // Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)
        val sampler = GraySampler.of(gray)
        gray.release()

        // Convert initial corners to ROI coordinates
        val roiCorners = initialCorners.map { (x, y) ->
            (x - searchRegion.x) to (y - searchRegion.y)
        }

        // TL=0, TR=1, BR=2, BL=3
        val tl = roiCorners[0]
        val tr = roiCorners[1]
        val br = roiCorners[2]
        val bl = roiCorners[3]

        // Calculate center of the quadrilateral
        val centerX = (tl.first + tr.first + br.first + bl.first) / 4
        val centerY = (tl.second + tr.second + br.second + bl.second) / 4

        // Scan from outside inward to find bezel→bright transitions
        val topScan = findEdgePointsOutward(sampler, tl, tr, centerX, centerY)
        val bottomScan = findEdgePointsOutward(sampler, bl, br, centerX, centerY)
        val leftScan = findEdgePointsOutward(sampler, tl, bl, centerX, centerY)
        val rightScan = findEdgePointsOutward(sampler, tr, br, centerX, centerY)

        // Fit consensus lines through edge points; RANSAC rejects rays that
        // overshot into interior content (text, table lines) or glare.
        val topLine = robustFitLine(topScan)
        val bottomLine = robustFitLine(bottomScan)
        val leftLine = robustFitLine(leftScan)
        val rightLine = robustFitLine(rightScan)

        if (topLine == null || bottomLine == null || leftLine == null || rightLine == null) {
            Log.debug {
                "ContourCornerRefiner: Gradient analysis - line fitting failed " +
                    "(points T:${topScan.points.size}, B:${bottomScan.points.size}, " +
                    "L:${leftScan.points.size}, R:${rightScan.points.size})"
            }
            return null
        }

        // Compute corner intersections with bounds checking
        val maxBound = maxOf(roi.cols(), roi.rows()).toDouble()
        val newTL = lineIntersection(topLine, leftLine, maxBound)
        val newTR = lineIntersection(topLine, rightLine, maxBound)
        val newBR = lineIntersection(bottomLine, rightLine, maxBound)
        val newBL = lineIntersection(bottomLine, leftLine, maxBound)

        if (newTL == null || newTR == null || newBR == null || newBL == null) {
            Log.debug { "ContourCornerRefiner: Gradient analysis - intersection computation failed (parallel lines or out of bounds)" }
            return null
        }

        // Validate the quadrilateral is reasonable
        val refinedCorners = listOf(newTL, newTR, newBR, newBL)
        if (!isValidQuadrilateral(refinedCorners, roiCorners)) {
            Log.debug { "ContourCornerRefiner: Gradient analysis - invalid quadrilateral shape" }
            return null
        }

        Log.debug { "ContourCornerRefiner: Gradient analysis found ${topScan.points.size + bottomScan.points.size + leftScan.points.size + rightScan.points.size} edge points" }

        // Convert back to image coordinates
        return listOf(
            (newTL.first + searchRegion.x) to (newTL.second + searchRegion.y),
            (newTR.first + searchRegion.x) to (newTR.second + searchRegion.y),
            (newBR.first + searchRegion.x) to (newBR.second + searchRegion.y),
            (newBL.first + searchRegion.x) to (newBL.second + searchRegion.y)
        )
    }

    /**
     * Find edge points by shooting rays FROM OUTSIDE INWARD, perpendicular to
     * the estimated edge. The black plastic bezel is always present, so each
     * ray starts beyond the edge estimate and scans inward looking for the
     * dark→bright transition.
     *
     * Shoots [RAYS_PER_EDGE] rays spread across the edge. Perpendicular rays
     * (rather than center-radial ones) stay well-conditioned near the edge
     * ends, so the spread can be wide — a longer lever arm for the line fit.
     */
    private fun findEdgePointsOutward(
        sampler: GraySampler,
        corner1: Pair<Double, Double>,
        corner2: Pair<Double, Double>,
        centerX: Double,
        centerY: Double
    ): EdgeScan {
        val edgePoints = mutableListOf<Pair<Double, Double>>()

        // Calculate edge direction vector
        val edgeDx = corner2.first - corner1.first
        val edgeDy = corner2.second - corner1.second
        val edgeLen = sqrt(edgeDx * edgeDx + edgeDy * edgeDy)

        if (edgeLen < 1) return EdgeScan(edgePoints, 0.0, 0.0)

        // Outward normal of the edge (flip so it points away from the center)
        var normalX = edgeDy / edgeLen
        var normalY = -edgeDx / edgeLen
        val midX = (corner1.first + corner2.first) / 2
        val midY = (corner1.second + corner2.second) / 2
        if ((midX - centerX) * normalX + (midY - centerY) * normalY < 0) {
            normalX = -normalX
            normalY = -normalY
        }

        val step = (RAY_SPAN_END - RAY_SPAN_START) / (RAYS_PER_EDGE - 1)
        for (k in 0 until RAYS_PER_EDGE) {
            val t = RAY_SPAN_START + k * step

            // Point along the edge estimate
            val edgeX = corner1.first + t * edgeDx
            val edgeY = corner1.second + t * edgeDy

            // Distance to center scales how far out we start and how deep we scan
            val toCenterX = edgeX - centerX
            val toCenterY = edgeY - centerY
            val outLen = sqrt(toCenterX * toCenterX + toCenterY * toCenterY)
            if (outLen < 1) continue

            // Start OUTSIDE - go 15% beyond the edge estimate
            val startX = edgeX + normalX * outLen * 0.15
            val startY = edgeY + normalY * outLen * 0.15

            // Scan INWARD looking for the dark→bright transition
            val scanLength = (outLen * 0.3).toInt().coerceAtLeast(50)

            // Limit how far inside the estimate we can go (15% of distance to center)
            // This allows for more tolerance while still preventing false edges
            val maxInward = outLen * 0.15

            val edgePoint = findEdgeAlongRay(
                sampler, startX, startY, -normalX, -normalY, scanLength,
                edgeX, edgeY, maxInward
            )
            if (edgePoint != null) {
                edgePoints.add(edgePoint)
            }
        }

        return EdgeScan(edgePoints, normalX, normalY)
    }

    /** Detected transition points for one edge plus the edge's outward normal. */
    private class EdgeScan(
        val points: List<Pair<Double, Double>>,
        val normalX: Double,
        val normalY: Double,
    )

    /**
     * Scan a ray from outside inward to find where the black bezel meets the
     * bright border/content, with sub-pixel localization.
     *
     * The intensity profile along the ray is sampled bilinearly and smoothed;
     * dark/bright hysteresis levels are derived from the profile's own range,
     * so the detection adapts to exposure. The coarse transition (first bright
     * sample after having been dark) is then refined to the maximum-gradient
     * position via parabolic interpolation.
     *
     * @param dx Direction X component (pointing inward toward center)
     * @param dy Direction Y component (pointing inward toward center)
     * @param maxInwardFromEstimate Maximum distance past the edge estimate (in the inward direction)
     */
    private fun findEdgeAlongRay(
        sampler: GraySampler,
        startX: Double,
        startY: Double,
        dx: Double,
        dy: Double,
        maxLength: Int,
        edgeEstimateX: Double,
        edgeEstimateY: Double,
        maxInwardFromEstimate: Double
    ): Pair<Double, Double>? {
        if (maxLength < 4) return null

        // Sample the intensity profile along the ray (NaN outside the image)
        val raw = DoubleArray(maxLength) { i ->
            sampler.sample(startX + i * dx, startY + i * dy)
        }

        // 3-tap smoothing over valid samples
        val profile = DoubleArray(maxLength) { i ->
            var sum = 0.0
            var n = 0
            for (j in (i - 1).coerceAtLeast(0)..(i + 1).coerceAtMost(maxLength - 1)) {
                val v = raw[j]
                if (!v.isNaN()) { sum += v; n++ }
            }
            if (n > 0) sum / n else Double.NaN
        }

        // Adaptive hysteresis levels from the profile's own intensity range
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (v in profile) {
            if (v.isNaN()) continue
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val contrast = maxV - minV
        if (contrast < MIN_RAY_CONTRAST) return null

        val darkLevel = minV + DARK_LEVEL_FRACTION * contrast
        val brightLevel = minV + BRIGHT_LEVEL_FRACTION * contrast

        // Walk inward: must see dark (bezel) first, then the rise to bright
        var wasInDark = false
        var coarse = -1
        for (i in 0 until maxLength) {
            val v = profile[i]
            if (v.isNaN()) continue

            if (v < darkLevel) {
                wasInDark = true
            }

            if (wasInDark && v > brightLevel) {
                // Reject transitions too far past the estimate in the inward
                // direction (signed projection of detected-minus-estimate onto
                // the inward direction; negative = outward, always fine).
                val vecX = (startX + i * dx) - edgeEstimateX
                val vecY = (startY + i * dy) - edgeEstimateY
                val projectionInward = vecX * dx + vecY * dy
                if (projectionInward < maxInwardFromEstimate) {
                    coarse = i
                    break
                }
            }
        }
        if (coarse < 0) return null

        // Sub-pixel: strongest positive gradient near the coarse crossing,
        // refined by parabolic interpolation over the gradient values.
        fun gradientAt(i: Int): Double {
            val a = profile[(i - 1).coerceAtLeast(0)]
            val b = profile[(i + 1).coerceAtMost(maxLength - 1)]
            return if (a.isNaN() || b.isNaN()) 0.0 else b - a
        }

        var bestIdx = coarse
        var bestGradient = gradientAt(coarse)
        for (i in (coarse - 4).coerceAtLeast(1) until (coarse + 3).coerceAtMost(maxLength - 1)) {
            val g = gradientAt(i)
            if (g > bestGradient) {
                bestGradient = g
                bestIdx = i
            }
        }

        var position = bestIdx.toDouble()
        if (bestIdx in 1 until maxLength - 1) {
            val gPrev = gradientAt(bestIdx - 1)
            val gNext = gradientAt(bestIdx + 1)
            val denom = gPrev - 2 * bestGradient + gNext
            if (abs(denom) > 1e-9) {
                val delta = 0.5 * (gPrev - gNext) / denom
                if (abs(delta) <= 1.0) position += delta
            }
        }

        return (startX + position * dx) to (startY + position * dy)
    }

    /**
     * Validate that the refined quadrilateral is reasonable.
     * Checks:
     * - Positive area (correct winding order)
     * - Area is within reasonable bounds compared to initial estimate
     * - No self-intersections (convex or at least simple polygon)
     */
    private fun isValidQuadrilateral(
        refined: List<Pair<Double, Double>>,
        initial: List<Pair<Double, Double>>
    ): Boolean {
        // Calculate areas using shoelace formula
        fun quadArea(corners: List<Pair<Double, Double>>): Double {
            var area = 0.0
            for (i in corners.indices) {
                val j = (i + 1) % corners.size
                area += corners[i].first * corners[j].second
                area -= corners[j].first * corners[i].second
            }
            return area / 2.0
        }

        val refinedArea = quadArea(refined)
        val initialArea = quadArea(initial)

        // Area should be positive (correct winding order) or we flip it
        val absRefinedArea = abs(refinedArea)
        val absInitialArea = abs(initialArea)

        // Check area is reasonable (between 50% and 150% of initial)
        if (absInitialArea > 0) {
            val areaRatio = absRefinedArea / absInitialArea
            if (areaRatio < 0.5 || areaRatio > 1.5) {
                Log.debug { "ContourCornerRefiner: Area ratio ${"%.2f".format(areaRatio)} outside acceptable range [0.5, 1.5]" }
                return false
            }
        }

        // Check for self-intersection by verifying corners are in expected relative positions
        // TL should be top-left of center, TR top-right, etc.
        val centerX = refined.map { it.first }.average()
        val centerY = refined.map { it.second }.average()

        val (tl, tr, br, bl) = refined
        val tlOk = tl.first < centerX && tl.second < centerY
        val trOk = tr.first > centerX && tr.second < centerY
        val brOk = br.first > centerX && br.second > centerY
        val blOk = bl.first < centerX && bl.second > centerY

        if (!tlOk || !trOk || !brOk || !blOk) {
            Log.debug { "ContourCornerRefiner: Corners not in expected positions relative to center" }
            return false
        }

        return true
    }

    /**
     * Fit a consensus line through edge points: RANSAC over point pairs
     * rejects outliers (rays that latched onto interior content), then a
     * least-squares refit over the inliers gives the final line.
     *
     * When several strong consensus lines exist — typically the display's
     * outer frame line and a near-parallel run of bright interior content
     * (title bar, table rule) — the OUTERMOST qualifying line wins: the
     * display border is by definition the outermost transition that is
     * consistent across rays.
     *
     * Returns line as [x1, y1, x2, y2] or null when no adequate consensus.
     */
    private fun robustFitLine(scan: EdgeScan): DoubleArray? {
        val points = scan.points
        if (points.size < 2) return null
        if (points.size == 2) return fitLine(points)

        // Collect consensus sets for every point pair
        val candidates = mutableListOf<Pair<List<Pair<Double, Double>>, Double>>()
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val (x1, y1) = points[i]
                val (x2, y2) = points[j]
                val len = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
                if (len < 1e-6) continue

                val inliers = points.filter { (px, py) ->
                    // Perpendicular distance from the candidate line
                    val cross = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
                    abs(cross) / len <= RANSAC_INLIER_TOLERANCE
                }
                // Outward-ness of the candidate: mean projection of its
                // inliers onto the edge's outward normal.
                val outwardness = inliers.sumOf { (px, py) ->
                    px * scan.normalX + py * scan.normalY
                } / inliers.size
                candidates.add(inliers to outwardness)
            }
        }
        if (candidates.isEmpty()) return null

        val bestCount = candidates.maxOf { it.first.size }
        if (bestCount < MIN_EDGE_INLIERS) {
            Log.debug { "ContourCornerRefiner: line consensus too weak ($bestCount/${points.size} inliers)" }
            return null
        }

        // Among comparably-strong consensus lines, take the outermost one
        val qualifyCount = (bestCount * CONSENSUS_QUALIFY_FRACTION).toInt()
            .coerceAtLeast(MIN_EDGE_INLIERS)
        val winner = candidates
            .filter { it.first.size >= qualifyCount }
            .maxBy { it.second }
        val bestInliers = winner.first

        if (bestInliers.size < points.size) {
            Log.debug { "ContourCornerRefiner: RANSAC kept ${bestInliers.size}/${points.size} edge points" }
        }

        return fitLine(bestInliers)
    }

    /**
     * Fit a line through points using least squares.
     * Returns line as [x1, y1, x2, y2] or null if fitting fails.
     */
    private fun fitLine(points: List<Pair<Double, Double>>): DoubleArray? {
        if (points.size < 2) return null

        // Use OpenCV's fitLine with L2 (least squares)
        val pointsMat = MatOfPoint2f(*points.map { Point(it.first, it.second) }.toTypedArray())
        val line = Mat()

        Imgproc.fitLine(pointsMat, line, Imgproc.DIST_L2, 0.0, 0.01, 0.01)

        // fitLine returns [vx, vy, x0, y0] - direction vector and point on line
        val vx = line.get(0, 0)[0]
        val vy = line.get(1, 0)[0]
        val x0 = line.get(2, 0)[0]
        val y0 = line.get(3, 0)[0]

        pointsMat.release()
        line.release()

        // Convert to two points format
        return doubleArrayOf(x0 - vx * 1000, y0 - vy * 1000, x0 + vx * 1000, y0 + vy * 1000)
    }

    /**
     * Bulk-copied grayscale pixel buffer with bilinear sampling. One JNI copy
     * per refinement call instead of one `Mat.get` per scanned pixel.
     */
    private class GraySampler(
        private val data: ByteArray,
        private val width: Int,
        private val height: Int
    ) {
        /** Bilinear sample at (x, y); NaN outside the image. */
        fun sample(x: Double, y: Double): Double {
            if (x < 0 || y < 0 || x > width - 1.0 || y > height - 1.0) return Double.NaN
            val x0 = x.toInt().coerceAtMost(width - 2).coerceAtLeast(0)
            val y0 = y.toInt().coerceAtMost(height - 2).coerceAtLeast(0)
            val fx = x - x0
            val fy = y - y0
            val i00 = (data[y0 * width + x0].toInt() and 0xFF).toDouble()
            val i10 = (data[y0 * width + x0 + 1].toInt() and 0xFF).toDouble()
            val i01 = (data[(y0 + 1) * width + x0].toInt() and 0xFF).toDouble()
            val i11 = (data[(y0 + 1) * width + x0 + 1].toInt() and 0xFF).toDouble()
            return (i00 * (1 - fx) + i10 * fx) * (1 - fy) + (i01 * (1 - fx) + i11 * fx) * fy
        }

        companion object {
            fun of(gray: Mat): GraySampler {
                val src = if (gray.isContinuous) gray else gray.clone()
                val data = ByteArray(src.rows() * src.cols())
                src.get(0, 0, data)
                if (src !== gray) src.release()
                return GraySampler(data, gray.cols(), gray.rows())
            }
        }
    }

    /**
     * Find corners using white contour detection.
     */
    private fun findByWhiteContours(
        roi: Mat,
        initialCorners: List<Pair<Double, Double>>,
        searchRegion: Rect
    ): List<Pair<Double, Double>>? {
        // Convert to HSV for white detection
        val hsv = Mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)

        // Define white/light gray color range in HSV
        // H: don't care (use full range 0-180)
        // S: low saturation (0-60) - white/gray is desaturated
        // V: high value (160-255) - white/light gray is bright
        val lowerWhite = Scalar(0.0, 0.0, 160.0)
        val upperWhite = Scalar(180.0, 60.0, 255.0)

        // Create mask for white pixels
        val whiteMask = Mat()
        Core.inRange(hsv, lowerWhite, upperWhite, whiteMask)

        // Morphological operations to connect white border segments
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(whiteMask, whiteMask, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.dilate(whiteMask, whiteMask, kernel, Point(-1.0, -1.0), 2)

        // Find contours - use RETR_EXTERNAL to only get outer contours
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            whiteMask,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        Log.debug { "ContourCornerRefiner: Found ${contours.size} white contours (HSV detection)" }

        // Find the best rectangular contour
        val bestContour = findBestRectangularContour(contours, roi, initialCorners, searchRegion)

        // Cleanup
        hsv.release()
        whiteMask.release()
        kernel.release()
        hierarchy.release()
        contours.forEach { it.release() }

        return bestContour
    }

    /**
     * Compute intersection point of two lines.
     * Each line is represented as [x1, y1, x2, y2].
     *
     * @param maxBound Maximum reasonable coordinate value (for bounds checking)
     */
    private fun lineIntersection(
        line1: DoubleArray,
        line2: DoubleArray,
        maxBound: Double = Double.MAX_VALUE
    ): Pair<Double, Double>? {
        val x1 = line1[0]
        val y1 = line1[1]
        val x2 = line1[2]
        val y2 = line1[3]

        val x3 = line2[0]
        val y3 = line2[1]
        val x4 = line2[2]
        val y4 = line2[3]

        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)

        // Reject parallel or nearly-parallel lines (unreliable intersection)
        if (abs(denom) < 0.001) return null

        val x = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / denom
        val y = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / denom

        // Bounds check - reject intersections far outside reasonable area
        if (maxBound != Double.MAX_VALUE) {
            if (x < -maxBound || x > 2 * maxBound || y < -maxBound || y > 2 * maxBound) {
                return null
            }
        }

        return x to y
    }

    /**
     * Find the best rectangular contour that matches our criteria.
     */
    private fun findBestRectangularContour(
        contours: List<MatOfPoint>,
        roi: Mat,
        initialCorners: List<Pair<Double, Double>>,
        searchRegion: Rect
    ): List<Pair<Double, Double>>? {
        // Calculate expected dimensions from initial corners
        val expectedWidth = initialCorners[1].first - initialCorners[0].first
        val expectedHeight = initialCorners[3].second - initialCorners[0].second
        val expectedArea = expectedWidth * expectedHeight
        val expectedAspectRatio = expectedWidth / expectedHeight

        Log.debug { "ContourCornerRefiner: Expected dimensions = ${expectedWidth.toInt()} x ${expectedHeight.toInt()}, aspect ratio = ${"%.2f".format(expectedAspectRatio)}" }

        var bestScore = 0.0
        var bestCorners: List<Pair<Double, Double>>? = null

        for (contour in contours) {
            // Approximate the contour to a polygon
            val contour2f = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            val epsilon = 0.02 * Imgproc.arcLength(contour2f, true)
            Imgproc.approxPolyDP(contour2f, approx, epsilon, true)

            // We're looking for a quadrilateral (4 corners)
            if (approx.rows() != 4) {
                contour2f.release()
                approx.release()
                continue
            }

            // Get the area
            val area = Imgproc.contourArea(contour)

            // Filter by area (should be similar to expected area)
            val areaRatio = area / expectedArea
            if (areaRatio < 0.5 || areaRatio > 1.5) {
                contour2f.release()
                approx.release()
                continue
            }

            // Get the bounding rect to check aspect ratio
            val boundingRect = Imgproc.boundingRect(contour)
            val aspectRatio = boundingRect.width.toDouble() / boundingRect.height
            val aspectRatioDiff = abs(aspectRatio - expectedAspectRatio)

            // Filter by aspect ratio (should be similar to expected)
            if (aspectRatioDiff > 0.5) {
                contour2f.release()
                approx.release()
                continue
            }

            // Skip convexity check - perspective distortion can make valid quadrilaterals non-convex

            // Score this contour
            // Prefer LARGER contours (closer to or exceeding expected size)
            // areaRatio > 1.0 means larger than expected (good)
            // areaRatio < 1.0 means smaller than expected (penalize more)
            val areaScore = if (areaRatio >= 1.0) {
                // Larger is good, but not too much larger
                1.0 - (areaRatio - 1.0) * 0.5
            } else {
                // Smaller is worse - penalize more heavily
                areaRatio * 0.8
            }
            val aspectScore = 1.0 - aspectRatioDiff / expectedAspectRatio
            val score = areaScore * 0.7 + aspectScore * 0.3

            if (score > bestScore) {
                bestScore = score

                // Extract corners and convert to image coordinates
                val corners = approx.toArray()

                // Sort corners to TL, TR, BR, BL order
                val sortedCorners = sortCorners(corners.map { it.x to it.y })

                // Convert from ROI coordinates to image coordinates
                bestCorners = sortedCorners.map { (x, y) ->
                    (x + searchRegion.x) to (y + searchRegion.y)
                }
            }

            contour2f.release()
            approx.release()
        }

        if (bestCorners != null) {
            Log.debug { "ContourCornerRefiner: Best contour score = ${"%.3f".format(bestScore)}" }
        }

        return bestCorners
    }

    /**
     * Sort corners into TL, TR, BR, BL order.
     */
    private fun sortCorners(corners: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        // Sort by y-coordinate to separate top and bottom pairs
        val sorted = corners.sortedBy { it.second }

        // Top two corners (sorted left to right)
        val top = sorted.take(2).sortedBy { it.first }
        val topLeft = top[0]
        val topRight = top[1]

        // Bottom two corners (sorted left to right)
        val bottom = sorted.takeLast(2).sortedBy { it.first }
        val bottomLeft = bottom[0]
        val bottomRight = bottom[1]

        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }
}
