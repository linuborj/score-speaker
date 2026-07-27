package se.linusborjesson.scorespeaker.processing

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.pipeline.Point2D
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The target's true geometry as measured from the image: where the bullseye
 * is and how many pixels one scoring ring spans. Both in the coordinate
 * space of the Mat the estimate was made from.
 */
data class TargetGeometry(
    val center: Point2D,
    val ringSpacingPx: Double,
)

/**
 * Measures [TargetGeometry] from a TARGET-cell image by locating the black
 * aiming area of the ISSF air-rifle target — the solid dark disc covering
 * rings 4–10 (outer radius 15.25 mm = [discRadiusInRings] × the 2.5 mm ring
 * step). The disc is the highest-contrast, most view-invariant landmark on
 * the display, so deriving centre + scale from it replaces two fitted
 * assumptions in the scoring path: "bullseye = geometric cell centre" and
 * the corpus-calibrated `ringSpacingRatio` constant (which bakes one
 * average rectification into all frames; the measured disc absorbs each
 * frame's actual rectification scale instead).
 *
 * Method — deliberately connectivity-free, because the white ring lines and
 * ring numbers printed on the disc slice its binary mask into arcs that
 * defeat contour/component fitting:
 *  1. Downscale to [workWidth], grayscale, Otsu-inverse → dark mask.
 *  2. Remove connected components touching the image border (screen
 *     background around the target card, outer rings clipped by the cell).
 *  3. Column/row-sum profiles of the mask. For a disc of radius r the
 *     column sum is the chord length `2·√(r² − (x−cx)²)`; the span of
 *     columns with sum ≥ [cutFraction] of the peak gives r and cx in
 *     closed form. The cut is deliberately *low*: columns near the cut sit
 *     beyond the outermost ring circle drawn on the disc, so the white
 *     ring lines and digits (which subtract from chords nearer the centre
 *     and would bias a half-peak measurement several percent small) don't
 *     touch it — while still being far above what thin ring curves or
 *     stray card digits contribute to any column.
 *  4. Sanity checks: horizontal/vertical radii agree, radius plausible
 *     for a SIUS layout, disc interior actually dark.
 *
 * Returns null when no plausible disc is found (blank test canvases, target
 * occluded, wildly wrong calibration) — callers fall back to the fitted
 * constants.
 */
class TargetGeometryEstimator(
    /** ISSF 10 m air rifle: black-disc outer edge at 15.25 mm / 2.5 mm per ring. */
    private val discRadiusInRings: Double = 6.1,
    /** Analysis resolution; the input is downscaled to this width. */
    private val workWidth: Int = 600,
    /** Plausible disc radius as a fraction of the cell width. */
    private val minRadiusRatio: Double = 0.10,
    private val maxRadiusRatio: Double = 0.45,
    /** Profile cut as a fraction of the peak — see the class doc. */
    private val cutFraction: Double = 0.15,
) {
    init {
        CoordinateTransform.ensureOpenCv()
    }

    fun estimate(targetCellMat: Mat): TargetGeometry? {
        if (targetCellMat.empty() || targetCellMat.cols() < 32 || targetCellMat.rows() < 32) return null
        val scale = workWidth.toDouble() / targetCellMat.cols()

        val work = Mat()
        val gray = Mat()
        val mask = Mat()
        try {
            Imgproc.resize(
                targetCellMat, work,
                Size(workWidth.toDouble(), targetCellMat.rows() * scale),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            if (work.channels() > 1) {
                Imgproc.cvtColor(work, gray, Imgproc.COLOR_BGR2GRAY)
            } else {
                work.copyTo(gray)
            }
            Imgproc.threshold(gray, mask, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

            removeBorderTouchingComponents(mask)

            val horizontal = cutSpan(profile(mask, sumColumns = true)) ?: return null
            val vertical = cutSpan(profile(mask, sumColumns = false)) ?: return null

            // chord(d) = cut·peak at d = r·√(1 − cut²) with peak ≈ 2r,
            // so span = 2·r·√(1 − cut²).
            val spanToRadius = 2.0 * sqrt(1.0 - cutFraction * cutFraction)
            val rx = horizontal.width / spanToRadius
            val ry = vertical.width / spanToRadius
            if (abs(rx - ry) > 0.2 * maxOf(rx, ry)) return null
            val r = (rx + ry) / 2.0
            if (r < minRadiusRatio * mask.cols() || r > maxRadiusRatio * mask.cols()) return null

            val cx = horizontal.center
            val cy = vertical.center
            if (!discInteriorIsDark(mask, cx, cy, r)) return null

            return TargetGeometry(
                center = Point2D(cx / scale, cy / scale),
                ringSpacingPx = (r / discRadiusInRings) / scale,
            )
        } finally {
            work.release()
            gray.release()
            mask.release()
        }
    }

    /** Zero out mask components that touch the image border. */
    private fun removeBorderTouchingComponents(mask: Mat) {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val badLabel = Mat()
        try {
            val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            for (i in 1 until count) {
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val touchesBorder = x <= 0 || y <= 0 ||
                    x + w >= mask.cols() || y + h >= mask.rows()
                if (touchesBorder) {
                    Core.compare(labels, Scalar(i.toDouble()), badLabel, Core.CMP_EQ)
                    mask.setTo(Scalar(0.0), badLabel)
                }
            }
        } finally {
            labels.release()
            stats.release()
            centroids.release()
            badLabel.release()
        }
    }

    /** Dark-pixel count per column (or per row), as a DoubleArray. */
    private fun profile(mask: Mat, sumColumns: Boolean): DoubleArray {
        val reduced = Mat()
        try {
            // dim 0 → single row (per-column sums); dim 1 → single column.
            Core.reduce(mask, reduced, if (sumColumns) 0 else 1, Core.REDUCE_SUM, CvType.CV_32S)
            val n = if (sumColumns) reduced.cols() else reduced.rows()
            return DoubleArray(n) { i ->
                val v = if (sumColumns) reduced.get(0, i) else reduced.get(i, 0)
                v[0] / 255.0
            }
        } finally {
            reduced.release()
        }
    }

    private data class Span(val center: Double, val width: Double)

    /** Contiguous span around the peak where the profile stays ≥ [cutFraction] × peak. */
    private fun cutSpan(profile: DoubleArray): Span? {
        if (profile.isEmpty()) return null
        val peakIdx = profile.indices.maxBy { profile[it] }
        val peak = profile[peakIdx]
        // A disc at the minimum plausible radius peaks at ≈ 2·minRadiusRatio·width.
        if (peak < 40.0) return null
        val level = peak * cutFraction
        var lo = peakIdx
        while (lo > 0 && profile[lo - 1] >= level) lo--
        var hi = peakIdx
        while (hi < profile.size - 1 && profile[hi + 1] >= level) hi++
        return Span(center = (lo + hi) / 2.0, width = (hi - lo + 1).toDouble())
    }

    /** The central region of a real aiming black is overwhelmingly dark. */
    private fun discInteriorIsDark(mask: Mat, cx: Double, cy: Double, r: Double): Boolean {
        val half = (r * 0.5).toInt()
        val x0 = (cx - half).toInt().coerceIn(0, mask.cols() - 1)
        val y0 = (cy - half).toInt().coerceIn(0, mask.rows() - 1)
        val x1 = (cx + half).toInt().coerceIn(x0 + 1, mask.cols())
        val y1 = (cy + half).toInt().coerceIn(y0 + 1, mask.rows())
        val roi = mask.submat(y0, y1, x0, x1)
        try {
            return Core.mean(roi).`val`[0] > 0.6 * 255.0
        } finally {
            roi.release()
        }
    }

}
