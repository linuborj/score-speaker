package se.linusborjesson.scorespeaker.processing

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.ocr.DigitTemplateMatcher
import kotlin.math.abs
import kotlin.math.max

/**
 * A shot the display placed *off* the target face.
 *
 * The SIUS doesn't omit a missed shot — it draws a different marker: a
 * small green badge pinned to the edge of the target cell, carrying the
 * shot number in dark digits, positioned in the direction the shot left
 * the target. [dx]/[dy] are the badge centre relative to the cell centre
 * in half-cell units (±1 at the rim), image convention: +x right, +y down.
 * They give a *direction only* — the badge sits at the rim, not where the
 * pellet went, so there is no ring offset to report and none is invented.
 */
data class MissDetection(
    val shotNumber: Int,
    val dx: Double,
    val dy: Double,
)

/**
 * Detects the off-target badge described by [MissDetection].
 *
 * **Positive detection, not absence.** "No green dot this frame" is not
 * evidence of a miss — it is also what defocus, occlusion, a mid-redraw
 * and detection drift look like, and announcing a miss on any of those
 * would be a confidently wrong announcement. So a miss is only ever
 * reported when the badge is actually *seen*, through gates that each
 * correspond to something physical:
 *
 * 1. **Size** — the badge is about half the linear size of the shot
 *    marker. Measured on the corpus: badge equivalent radius 0.019 of
 *    cell width, marker 0.032–0.036, two clusters with a wide gap.
 * 2. **At the rim** — the badge centre sits ≥ [minEdge] of the way to the
 *    cell boundary; no real marker in the corpus exceeds 0.72.
 * 3. **Roughly square** — a blank display throws long thin green slivers
 *    at the screen edge that pass (1) and (2); a badge is square.
 * 4. **The shot number** — the badge carries it, so it is read and must
 *    equal the shot the Cell D reader just confirmed. This is the same
 *    parse-or-null gate the shot sequence already rests on: a stray green
 *    patch has no digits in it.
 *
 * Gate 4 needs [shotTemplateMatcher]; without one the detector runs on
 * gates 1–3 alone, which is weaker — [requireGlyphConfirmation] decides
 * whether that is allowed to report a miss at all.
 *
 * If more than one candidate survives, the detector refuses (returns
 * null) rather than picking — the same singularity rule [GreenDotDetector]
 * applies to the marker.
 */
class MissBadgeDetector(
    /** Lower bound on badge radius, as a fraction of cell width. */
    minRadiusRatio: Double = 0.013,
    /** Upper bound on badge radius, as a fraction of cell width. */
    maxRadiusRatio: Double = 0.026,
    /** How far to the rim the badge centre must sit (0 = centre, 1 = edge). */
    private val minEdge: Double = 0.85,
    /** Max long-side / short-side ratio of the badge's bounding box. */
    private val maxAspect: Double = 1.7,
    /** Read the badge's own digits and require them to match. */
    private val requireGlyphConfirmation: Boolean = true,
) {
    /** The alphabet used for gate 4. Same shot-font the Cell D reader uses. */
    var shotTemplateMatcher: DigitTemplateMatcher? = null

    private val green = GreenDotDetector(
        minRadiusRatio = minRadiusRatio,
        maxRadiusRatio = maxRadiusRatio,
    )

    /** Last candidate's badge read, for debug surfaces. Null when not attempted. */
    var lastBadgeRead: String? = null
        private set

    /**
     * The off-target badge for [expectedShot], or null.
     *
     * [targetCell] is the rectified TARGET cell in BGR — the same Mat the
     * green-dot path runs on. [expectedShot] is the shot number the Cell D
     * reader confirmed this frame; the badge must agree with it.
     */
    fun detect(targetCell: Mat, expectedShot: Int): MissDetection? {
        lastBadgeRead = null
        if (targetCell.empty()) return null
        val w = targetCell.cols().toDouble()
        val h = targetCell.rows().toDouble()

        val candidates = green.candidates(targetCell).filter { blob ->
            val edge = max(abs(blob.centroidX / w - 0.5), abs(blob.centroidY / h - 0.5)) * 2
            val long = max(blob.boundingRect.width, blob.boundingRect.height).toDouble()
            val short = kotlin.math.min(blob.boundingRect.width, blob.boundingRect.height).toDouble()
            edge >= minEdge && short > 0 && long / short <= maxAspect
        }
        // Two badges means something upstream is wrong — refuse, don't pick.
        val badge = candidates.singleOrNull() ?: return null

        val matcher = shotTemplateMatcher
        if (matcher != null) {
            val read = readBadge(targetCell, badge.boundingRect, matcher)
            lastBadgeRead = read
            if (read?.toIntOrNull() != expectedShot) return null
        } else if (requireGlyphConfirmation) {
            return null
        }

        return MissDetection(
            shotNumber = expectedShot,
            dx = (badge.centroidX / w - 0.5) * 2,
            dy = (badge.centroidY / h - 0.5) * 2,
        )
    }

    /**
     * Read the badge's digits. They are *dark on green*, the inverse of
     * the white-on-dark shot index, so the crop is reduced by max(R,G,B):
     * the green ground stays bright and the digits stay dark, which is the
     * polarity [DigitTemplateMatcher]'s Otsu step expects to flip.
     */
    private fun readBadge(cell: Mat, rect: Rect, matcher: DigitTemplateMatcher): String? {
        // Inset slightly: the badge's own border is a hard edge that would
        // otherwise segment as a component.
        val inset = (kotlin.math.min(rect.width, rect.height) * 0.12).toInt().coerceAtLeast(1)
        val inner = Rect(
            rect.x + inset,
            rect.y + inset,
            (rect.width - 2 * inset).coerceAtLeast(1),
            (rect.height - 2 * inset).coerceAtLeast(1),
        )
        if (inner.x < 0 || inner.y < 0 ||
            inner.x + inner.width > cell.cols() || inner.y + inner.height > cell.rows()
        ) {
            return null
        }
        val crop = Mat(cell, inner)
        val reduced = Mat()
        return try {
            if (crop.channels() == 1) {
                crop.copyTo(reduced)
            } else {
                val channels = ArrayList<Mat>()
                Core.split(crop, channels)
                Core.max(channels[0], channels[1], reduced)
                Core.max(reduced, channels[2], reduced)
                channels.forEach { it.release() }
            }
            // Upscale: badge digits render at roughly a third the height of
            // the Cell D shot index, and NCC on a handful of pixels is noise.
            val scaled = Mat()
            val factor = (60.0 / reduced.rows()).coerceAtLeast(1.0)
            Imgproc.resize(
                reduced, scaled,
                org.opencv.core.Size(reduced.cols() * factor, reduced.rows() * factor),
                0.0, 0.0, Imgproc.INTER_CUBIC,
            )
            try {
                matcher.recognise(scaled)
            } finally {
                scaled.release()
            }
        } finally {
            reduced.release()
            crop.release()
        }
    }
}
