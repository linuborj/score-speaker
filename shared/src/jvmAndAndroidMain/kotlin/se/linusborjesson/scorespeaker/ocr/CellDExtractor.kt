package se.linusborjesson.scorespeaker.ocr

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.Log
import se.linusborjesson.scorespeaker.pipeline.CellExtractor
import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import java.io.File

/**
 * Reads the current shot number from Cell D — the small white shot index on
 * the left of the big green score digits, e.g. the "1P" of "1P 7.6".
 *
 * The reader is [DigitTemplateMatcher] NCC matching against a real-glyph
 * alphabet (0–9 + P) — the semantic gate: only one of eleven known shapes
 * can ever be read, and degraded input (defocus, mid-redraw) matches
 * nothing and returns unread rather than a hallucination. There is no OCR
 * engine anywhere (see docs/EXPERIMENTS.md for how
 * the pipeline arrived here).
 *
 * The score digits are deliberately NOT read — the product score is derived
 * geometrically from the green shot marker. The score region only feeds
 * [greenDominance], a cheap presence gate: lit green digits mean a shot is
 * on the display at all.
 */
class CellDExtractor : CellExtractor {

    data class Result(
        val rawText: String,
        val value: ScoreShotValue?,
        /** Min per-glyph NCC over all segmented glyphs (0..1); null = no read. */
        val shotConfidence: Float? = null,
    )

    /** When non-null, the reduced shot crop is saved here for debugging. */
    var debugOutputDir: File? = null

    /**
     * The real-glyph alphabet. Null (failed to load) reads nothing —
     * there is no fallback reader by design.
     */
    var shotTemplateMatcher: DigitTemplateMatcher? = null

    /**
     * The most recent [extractDetail] result — for debug surfaces that sit
     * behind the frame cache (a cache hit means the cell content matched,
     * so the last detail still describes it).
     */
    var lastDetail: Result? = null
        private set

    /**
     * [CellExtractor] entry point — returns only the typed value (or null).
     * Use [extractDetail] for the verbose result with the raw glyph read.
     */
    override fun extract(cellMat: Mat): CellValue? = extractDetail(cellMat).value

    /** Verbose form: returns the raw glyph read alongside the parsed value. */
    fun extractDetail(cellMat: Mat): Result =
        extractDetailInternal(cellMat).also { lastDetail = it }

    private fun extractDetailInternal(cellMat: Mat): Result {
        // Presence gate: the SIUS score is always bright green when a shot
        // is displayed. A blank or idle cell has no green-dominant pixels,
        // so we don't even look for shot digits.
        if (greenDominance(cellMat, scoreRect(cellMat.cols(), cellMat.rows())) < MIN_GREEN_DOMINANCE) {
            Log.debug { "CellDExtractor: score region not green-dominant — blank cell" }
            return Result("(blank)", null)
        }

        val matcher = shotTemplateMatcher ?: return Result("", null)

        val shotRect = shotRect(cellMat.cols(), cellMat.rows())
        return reducedShotCrop(cellMat, shotRect).useMatResult { reduced ->
            debugOutputDir?.let { dir ->
                dir.mkdirs()
                Imgcodecs.imwrite(File(dir, "D_shot.png").absolutePath, reduced)
            }
            val text = matcher.recognise(reduced).orEmpty()
            // Min over *all* segmented glyphs, rejected ones included — a
            // dropped glyph is a partial read and must pull confidence down.
            val confidence = matcher.lastMatches
                .minOfOrNull { it.score }
                ?.toFloat()
                ?.coerceIn(0f, 1f)
            Log.debug { "CellDExtractor: shot='$text' ($confidence)" }
            Result(text, parse(text), shotConfidence = confidence)
        }
    }

    /**
     * The reduced shot crop exactly as the matcher sees it — for the debug
     * panel, so a bad *crop* can be told from a bad *match*. Caller owns
     * the returned Mat.
     */
    fun debugProcessedShot(cellMat: Mat): Mat =
        reducedShotCrop(cellMat, shotRect(cellMat.cols(), cellMat.rows()))

    /**
     * min(R,G,B)-reduced native-size crop of [rect]: white shot digits stay
     * bright while the green score digits bleeding into the crop collapse
     * to their red/blue floor. The exact input GlyphAlphabetTools.evaluate
     * validated. Caller owns the returned Mat.
     */
    private fun reducedShotCrop(cellMat: Mat, rect: Rect): Mat {
        val safe = Rect(
            rect.x.coerceIn(0, cellMat.cols() - 1),
            rect.y.coerceIn(0, cellMat.rows() - 1),
            rect.width.coerceAtMost(cellMat.cols() - rect.x).coerceAtLeast(1),
            rect.height.coerceAtMost(cellMat.rows() - rect.y).coerceAtLeast(1),
        )
        val crop = Mat(cellMat, safe)
        val reduced = Mat()
        try {
            if (cellMat.channels() > 1) {
                val channels = ArrayList<Mat>()
                Core.split(crop, channels)
                Core.min(channels[0], channels[1], reduced)
                Core.min(reduced, channels[2], reduced)
                channels.forEach { it.release() }
            } else {
                crop.copyTo(reduced)
            }
        } finally {
            crop.release()
        }
        return reduced
    }

    private inline fun <T> Mat.useMatResult(block: (Mat) -> T): T =
        try { block(this) } finally { release() }

    /**
     * Mean of `G - max(R, B)` over the bright-green pixels of [rect] in the
     * BGR [src] — high for genuine LED score digits, near zero for noise,
     * white text, or border lines. Returns 0.0 when almost no pixel clears
     * the brightness bar (nothing resembling lit digits at all).
     */
    private fun greenDominance(src: Mat, rect: Rect): Double {
        val safe = Rect(
            rect.x.coerceIn(0, src.cols() - 1),
            rect.y.coerceIn(0, src.rows() - 1),
            rect.width.coerceAtMost(src.cols() - rect.x).coerceAtLeast(1),
            rect.height.coerceAtMost(src.rows() - rect.y).coerceAtLeast(1),
        )
        val crop = Mat(src, safe)
        val channels = ArrayList<Mat>(3)
        Core.split(crop, channels)
        val (blue, green, red) = channels
        val brightMask = Mat()
        val maxRB = Mat()
        val dominance = Mat()
        try {
            Imgproc.threshold(green, brightMask, 140.0, 255.0, Imgproc.THRESH_BINARY)
            val brightCount = Core.countNonZero(brightMask)
            if (brightCount < 0.005 * safe.width * safe.height) return 0.0
            Core.max(red, blue, maxRB)
            // Saturating 8-bit subtraction: negative dominance clamps to 0,
            // which is fine — those pixels aren't green either way.
            Core.subtract(green, maxRB, dominance)
            return Core.mean(dominance, brightMask).`val`[0]
        } finally {
            channels.forEach { it.release() }
            brightMask.release()
            maxRB.release()
            dominance.release()
            crop.release()
        }
    }

    companion object {
        /**
         * The shot and score sub-regions of Cell D, as functions of the cell
         * size so the debug overlay can draw *exactly* what the reader
         * crops. Shot is bottom-left; score (used only by the presence
         * gate) is right-half full-height. Splits (35% / 32%) validated
         * against the real display via the debug overlay's region quads.
         */
        fun shotRect(w: Int, h: Int): Rect {
            val padX = (w * 0.04).toInt().coerceAtLeast(2)
            val padY = (h * 0.08).toInt().coerceAtLeast(2)
            return Rect(padX, (h * 0.40).toInt(),
                (w * 0.35).toInt() - padX, h - (h * 0.40).toInt() - padY)
        }

        fun scoreRect(w: Int, h: Int): Rect {
            val padX = (w * 0.04).toInt().coerceAtLeast(2)
            val padY = (h * 0.08).toInt().coerceAtLeast(2)
            return Rect((w * 0.32).toInt(), padY,
                w - (w * 0.32).toInt() - padX, h - 2 * padY)
        }

        // Minimum mean G−max(R,B) over bright pixels for the score region to
        // count as lit green digits. Real LED digits measure ~130–150 in the
        // rectified corpus; gray noise and white text sit near 0.
        private const val MIN_GREEN_DOMINANCE = 40.0

        // Shot: <int> with optional trailing "P"
        private val shotPattern = Regex("""^(\d{1,2})\s*([Pp])?$""")

        /**
         * Parse a glyph read like "1P" / "20" into a [ScoreShotValue].
         * The shot number is the sequence gate: an unparseable or
         * out-of-range read is null — unread, never guessed. The score
         * field stays null; the green-dot measurement fills it downstream.
         */
        fun parse(shotText: String): ScoreShotValue? {
            val m = shotPattern.matchEntire(shotText.trim()) ?: return null
            val shot = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..60 } ?: return null
            val mode = m.groupValues[2].uppercase().ifEmpty { null }
            return ScoreShotValue(shot = shot.toString(), mode = mode, score = null)
        }
    }
}
