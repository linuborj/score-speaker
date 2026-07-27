package se.linusborjesson.scorespeaker.ocr

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Lightweight OCR alternative for known fixed-font LED digit/letter content.
 *
 * Pipeline:
 *  1. Binarise the input via Otsu (LED text → white, LCD background → black).
 *  2. Connected-components → one bounding box per glyph.
 *  3. For each glyph: normalise size, template-match against every known
 *     character; pick best by NCC (TM_CCOEFF_NORMED).
 *  4. Sort left-to-right, return the recognised string.
 *
 * This is intentionally narrow — works for the SIUS digit font where glyphs
 * don't touch and the alphabet is small (10 digits + a few letters). The
 * payoff: sub-millisecond per-cell cost, a real per-glyph confidence, and
 * a reader that cannot output a shape it has never seen.
 *
 * Build templates with [registerCharacter]; the typical bootstrap is to
 * extract one clean instance of each glyph from the annotated test data.
 * Templates can be at any resolution — they're scaled to a common height
 * at match time.
 */
class DigitTemplateMatcher(
    /** All glyphs are rescaled to this height before matching. */
    private val normalisedHeight: Int = 60,
    /** Min NCC score for a match to count. 0.7 is fairly strict; 0.5 is permissive. */
    private val minScore: Double = 0.55,
) {
    /**
     * Character templates, already normalised to [normalisedHeight] and
     * BYTE_GRAY. Multiple instances per character: real glyphs vary with
     * capture conditions (exposure, blur, render size), and a glyph is
     * matched against *every* instance — best score wins. Each collected
     * instance can only improve recall; a bad instance can only lose to a
     * better-scoring correct one, never veto it.
     */
    private val templates = mutableMapOf<Char, MutableList<Mat>>()

    /** Register a template instance for the given character (appends). */
    fun registerCharacter(ch: Char, image: Mat) {
        templates.getOrPut(ch) { mutableListOf() } += normalise(image)
    }

    /** Per-glyph diagnostics: best character and its NCC score, including sub-threshold ones. */
    data class GlyphMatch(val char: Char?, val score: Double, val accepted: Boolean)

    /** Last [recognise] call's per-glyph matches, left to right — for tuning tools. */
    var lastMatches: List<GlyphMatch> = emptyList()
        private set

    fun recognise(cell: Mat): String? {
        if (templates.isEmpty()) return null

        // 1. Binarise — work in a grayscale Mat (single channel) so connectedComponents accepts it.
        val gray = Mat()
        val binary = Mat()
        try {
            if (cell.channels() > 1) Imgproc.cvtColor(cell, gray, Imgproc.COLOR_BGR2GRAY) else cell.copyTo(gray)
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

            // Otsu yields white-on-black for our LED-on-LCD source typically; if it
            // came out reversed (more white than black), flip so glyphs are foreground.
            val whitePx = Core.countNonZero(binary)
            val total = binary.rows() * binary.cols()
            if (whitePx > total / 2) Core.bitwise_not(binary, binary)

            // 2. Connected components → bounding boxes.
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val count = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids)

            // 3. Filter to plausible glyph components (skip noise + image-edge artifacts).
            data class Glyph(val rect: Rect, val area: Int)
            val rawGlyphs = mutableListOf<Glyph>()
            val cellArea = binary.rows() * binary.cols()
            for (i in 1 until count) {
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val a = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()
                if (a < cellArea / 1000) continue
                if (w >= binary.cols() - 2 || h >= binary.rows() - 2) continue
                if (w > h * 2.5 || h > w * 8) continue
                rawGlyphs += Glyph(Rect(x, y, w, h), a)
            }
            labels.release(); stats.release(); centroids.release()
            if (rawGlyphs.isEmpty()) return null

            // Drop components much shorter than the tallest one: the decimal-point
            // blob is ~10-15% of digit height. Anything < 40% of max height is
            // assumed punctuation/noise, not a digit.
            val maxH = rawGlyphs.maxOf { it.rect.height }
            val glyphs = rawGlyphs.filter { it.rect.height >= maxH * 0.4 }
            if (glyphs.isEmpty()) return null

            // 4. For each glyph, template-match against every known character.
            //    Sort glyphs left-to-right.
            val sorted = glyphs.sortedBy { it.rect.x }
            val sb = StringBuilder()
            val matches = mutableListOf<GlyphMatch>()
            for (glyph in sorted) {
                val crop = Mat(binary, glyph.rect)
                try {
                    val normalised = normalise(crop)
                    try {
                        val (bestChar, bestScore) = bestMatch(normalised)
                        val accepted = bestChar != null && bestScore >= minScore
                        matches += GlyphMatch(bestChar, bestScore, accepted)
                        if (accepted) sb.append(bestChar)
                    } finally {
                        normalised.release()
                    }
                } finally {
                    crop.release()
                }
            }
            lastMatches = matches
            return sb.toString().ifEmpty { null }
        } finally {
            gray.release()
            binary.release()
        }
    }

    /** Best NCC score for [glyph] over every instance of every character. */
    private fun bestMatch(glyph: Mat): Pair<Char?, Double> {
        var bestChar: Char? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for ((ch, instances) in templates) {
            for (tpl in instances) {
                // Resize template to match glyph dimensions if needed (both
                // are normalised to [normalisedHeight] but widths differ).
                val tplResized = if (tpl.rows() != glyph.rows() || tpl.cols() != glyph.cols()) {
                    val r = Mat()
                    Imgproc.resize(tpl, r, Size(glyph.cols().toDouble(), glyph.rows().toDouble()))
                    r
                } else {
                    tpl
                }
                try {
                    val result = Mat()
                    try {
                        Imgproc.matchTemplate(glyph, tplResized, result, Imgproc.TM_CCOEFF_NORMED)
                        val mm = Core.minMaxLoc(result)
                        if (mm.maxVal > bestScore) {
                            bestScore = mm.maxVal
                            bestChar = ch
                        }
                    } finally {
                        result.release()
                    }
                } finally {
                    if (tplResized !== tpl) tplResized.release()
                }
            }
        }
        return bestChar to bestScore
    }

    /**
     * Rescale [image] to [normalisedHeight] rows, preserving aspect ratio.
     * Output is always BYTE_GRAY for matchTemplate compatibility.
     */
    private fun normalise(image: Mat): Mat {
        val gray = Mat()
        if (image.channels() > 1) Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY) else image.copyTo(gray)
        val ratio = normalisedHeight.toDouble() / gray.rows()
        val newW = (gray.cols() * ratio).toInt().coerceAtLeast(1)
        val out = Mat()
        Imgproc.resize(gray, out, Size(newW.toDouble(), normalisedHeight.toDouble()))
        gray.release()
        return out
    }
}
