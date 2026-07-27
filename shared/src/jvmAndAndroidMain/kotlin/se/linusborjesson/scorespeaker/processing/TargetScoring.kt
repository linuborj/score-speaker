package se.linusborjesson.scorespeaker.processing

import org.opencv.core.Mat
import se.linusborjesson.scorespeaker.pipeline.Point2D
import kotlin.math.sqrt

/**
 * Convert a green-dot position inside the TARGET cell into a decimal score
 * and a per-axis offset from the target centre.
 *
 * Linear decimal scoring: `score = 10.9 - distance_in_rings` (centroid-to-
 * centre distance, no shot-edge correction — the green dot on the SIUS LCD
 * is a *marker* of where the shot landed, not a rendering of the physical
 * bullet hole, so its drawn radius is irrelevant).
 *
 * Centre and ring scale come from [geometryEstimator] when it can locate
 * the black aiming area in the frame — per-frame measured geometry absorbs
 * each calibration's actual rectification scale and bullseye position.
 * When estimation fails (target occluded, synthetic canvases without rings)
 * the pre-measured-geometry model is the fallback: bullseye assumed at
 * [geometricTargetCenter] and scale from the corpus-fitted
 * [ringSpacingRatio]. See `TargetScoringCorpusTest` for residuals of both
 * paths.
 */
class TargetScoring(
    /**
     * Fallback pixels-per-ring as a fraction of the TARGET cell width.
     * Calibrated to 0.0376 — the mean implied ratio across the corpus's
     * normal-angle cases (0.0370, 0.0375, 0.0384). The one extreme-angle
     * case in the corpus drifts to ~0.044 — exactly the rectification-scale
     * error the measured-geometry path absorbs automatically.
     */
    val ringSpacingRatio: Double = 0.0376,
    /**
     * Measures centre + ring spacing from the frame itself. Null disables
     * the measured path entirely (constants only).
     */
    val geometryEstimator: TargetGeometryEstimator? = TargetGeometryEstimator(),
) {
    // The estimate is stable for a given calibration (the target doesn't
    // move within the rectified cell), so cache it per cell size — measure()
    // runs on every frame with a detected dot. A size change invalidates;
    // a *recalibration that rounds to the same cell size* does not, which
    // is why callers must call [invalidateGeometry] on every full
    // re-detection. Estimation *failures* are not cached: they can be
    // transient (arm over the target) and a retry costs ~ms.
    private var cachedGeometry: TargetGeometry? = null
    private var cachedCols: Int = -1
    private var cachedRows: Int = -1

    /**
     * Drop the cached geometry. Call whenever the rectification may have
     * changed without the cell size changing — i.e. after any full
     * detection (as opposed to a warm-frame corner refinement). Cheap: the
     * next measured frame re-estimates once (~ms).
     */
    fun invalidateGeometry() {
        cachedGeometry = null
        cachedCols = -1
        cachedRows = -1
    }

    /**
     * Measure a single dot detection. Returns null if [targetCellMat] is
     * empty.
     *
     * Coordinate convention: positive `offsetXRings` = right of centre,
     * positive `offsetYRings` = below centre — matches OpenCV's image-pixel
     * convention (origin top-left, y grows downward).
     */
    fun measure(targetCellMat: Mat, dotCentroidInCellPx: Point2D): ShotMeasurement? {
        if (targetCellMat.empty()) return null

        measuredGeometry(targetCellMat)?.let { geometry ->
            return ShotMeasurement.fromRingOffset(
                dxRings = (dotCentroidInCellPx.x - geometry.center.x) / geometry.ringSpacingPx,
                dyRings = (dotCentroidInCellPx.y - geometry.center.y) / geometry.ringSpacingPx,
            )
        }

        val centre = geometricTargetCenter(targetCellMat) ?: return null
        val offset = Point2D(
            dotCentroidInCellPx.x - centre.x,
            dotCentroidInCellPx.y - centre.y,
        )
        return ShotMeasurement.fromCellOffset(offset, targetCellMat.cols(), ringSpacingRatio)
    }

    /**
     * The centre (cell px) scoring would use for this cell — measured
     * geometry when available, otherwise [geometricTargetCenter]. For
     * debug overlays; cheap on repeat calls thanks to the geometry cache.
     */
    fun centerFor(targetCellMat: Mat): Point2D? {
        if (targetCellMat.empty()) return null
        measuredGeometry(targetCellMat)?.let { return it.center }
        return geometricTargetCenter(targetCellMat)
    }

    private fun measuredGeometry(targetCellMat: Mat): TargetGeometry? {
        val estimator = geometryEstimator ?: return null
        if (targetCellMat.cols() == cachedCols && targetCellMat.rows() == cachedRows) {
            cachedGeometry?.let { return it }
        }
        val geometry = estimator.estimate(targetCellMat) ?: return null
        cachedGeometry = geometry
        cachedCols = targetCellMat.cols()
        cachedRows = targetCellMat.rows()
        return geometry
    }
}

/**
 * The fallback bullseye position: the TARGET cell's geometric centre,
 * content-blind. Works well enough when the cell ratios in
 * `CellLayout.siusDisplayCells()` are tuned correctly and the four corners
 * are reasonably accurate — visual testing on the corpus shows the
 * deviation is dominated by corner-detection error, not cell-ratio error.
 * Returns null for an empty Mat.
 */
fun geometricTargetCenter(targetCellMat: Mat): Point2D? {
    if (targetCellMat.empty()) return null
    return Point2D(targetCellMat.cols() / 2.0, targetCellMat.rows() / 2.0)
}

/**
 * Score + per-axis offset for one detected shot.
 *
 * The [score] is computed from the dot's distance to centre — useful as a
 * cross-check against Cell D's OCR'd score (the canonical value the SIUS
 * itself reports). The offset components are the primary signal for the
 * announcer: "shot 7, score 7.5, down 3, left 1" formats the offset's
 * sign + magnitude into directional language.
 *
 * Sign convention follows image coordinates:
 *   - positive [offsetXRings] = right of centre, negative = left
 *   - positive [offsetYRings] = below centre, negative = above
 *
 * [distanceRings] is `hypot(offsetXRings, offsetYRings)`.
 */
data class ShotMeasurement(
    val score: Double,
    val offsetXRings: Double,
    val offsetYRings: Double,
    val distanceRings: Double,
) {
    companion object {
        /** Build a measurement from a centre-relative offset already in ring units. */
        fun fromRingOffset(dxRings: Double, dyRings: Double): ShotMeasurement {
            val distanceRings = sqrt(dxRings * dxRings + dyRings * dyRings)
            return ShotMeasurement(
                score = (10.9 - distanceRings).coerceAtLeast(0.0),
                offsetXRings = dxRings,
                offsetYRings = dyRings,
                distanceRings = distanceRings,
            )
        }

        /**
         * Build a measurement from a centre-relative offset in TARGET-cell
         * pixels. Returns null when [ringSpacingRatio] × [cellWidth] is not
         * strictly positive (no meaningful ring scale to divide by).
         */
        fun fromCellOffset(
            offsetCellPx: Point2D,
            cellWidth: Int,
            ringSpacingRatio: Double,
        ): ShotMeasurement? {
            val ringSpacingPx = ringSpacingRatio * cellWidth
            if (ringSpacingPx <= 0.0) return null
            return fromRingOffset(
                dxRings = offsetCellPx.x / ringSpacingPx,
                dyRings = offsetCellPx.y / ringSpacingPx,
            )
        }
    }
}
