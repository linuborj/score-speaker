package se.linusborjesson.scorespeaker.processing

import android.content.Context
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.Log
import se.linusborjesson.scorespeaker.ocr.DigitTemplateMatcher

/**
 * Loads the real-glyph shot-font alphabet (assets/glyphs/shot-font/<char>/,
 * harvested by the desktop GlyphAlphabetTools from annotated captures) into
 * a [DigitTemplateMatcher]. Every instance per character is registered —
 * the matcher takes the best score across instances, so glyphs collected
 * under different capture conditions widen recall.
 *
 * Returns false when the alphabet is missing/incomplete — caller should
 * then leave [se.linusborjesson.scorespeaker.ocr.CellDExtractor]'s matcher
 * unset, which reads nothing (there is no fallback reader).
 */
fun DigitTemplateMatcher.loadShotFontFromAssets(context: Context): Boolean {
    return try {
        val root = "glyphs/shot-font"
        val chars = context.assets.list(root)?.sorted() ?: return false
        val coveredChars = mutableSetOf<Char>()
        for (ch in chars) {
            if (ch.length != 1) continue
            for (file in context.assets.list("$root/$ch")?.sorted() ?: continue) {
                context.assets.open("$root/$ch/$file").use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream) ?: return@use
                    val mat = Mat()
                    Utils.bitmapToMat(bitmap, mat)
                    val gray = Mat()
                    try {
                        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                        registerCharacter(ch.single(), gray)
                        coveredChars += ch.single()
                    } finally {
                        gray.release()
                        mat.release()
                    }
                }
            }
        }
        val complete = coveredChars.containsAll(('0'..'9').toList() + 'P')
        if (!complete) Log.warn { "DigitTemplateMatcher: shot-font alphabet incomplete ($coveredChars)" }
        complete
    } catch (e: Exception) {
        Log.warn { "DigitTemplateMatcher: failed to load shot-font alphabet: ${e.message}" }
        false
    }
}
