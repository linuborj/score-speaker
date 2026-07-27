package se.linusborjesson.scorespeaker.processing

import org.opencv.imgcodecs.Imgcodecs
import se.linusborjesson.scorespeaker.Log
import se.linusborjesson.scorespeaker.ocr.DigitTemplateMatcher
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import java.io.File

/**
 * Desktop counterpart of the Android asset loader: loads a glyph alphabet
 * from `<dir>/<char>/<instance>.png` (the layout GlyphAlphabetTools
 * harvests into `test-data/glyphs/shot-font/`). Every instance per
 * character is registered — the matcher takes the best score across
 * instances.
 *
 * Returns false when the alphabet is missing/incomplete (0–9 + P) —
 * caller should then leave the extractor's matcher unset, which reads
 * nothing (there is no fallback reader).
 */
fun DigitTemplateMatcher.loadAlphabetFromDirectory(dir: File): Boolean {
    if (!dir.isDirectory) return false
    CoordinateTransform.ensureOpenCv()
    val covered = mutableSetOf<Char>()
    for (charDir in dir.listFiles()?.sortedBy { it.name } ?: return false) {
        val name = charDir.name
        if (!charDir.isDirectory || name.length != 1) continue
        for (glyph in charDir.listFiles()?.sortedBy { it.name } ?: continue) {
            val mat = Imgcodecs.imread(glyph.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            if (mat.empty()) { mat.release(); continue }
            try {
                registerCharacter(name.single(), mat)
                covered += name.single()
            } finally {
                mat.release()
            }
        }
    }
    val complete = covered.containsAll(('0'..'9').toList() + 'P')
    if (!complete) Log.warn { "DigitTemplateMatcher: alphabet at $dir incomplete ($covered)" }
    return complete
}
