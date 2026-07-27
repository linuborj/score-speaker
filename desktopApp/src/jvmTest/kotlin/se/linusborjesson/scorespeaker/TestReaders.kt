package se.linusborjesson.scorespeaker

import se.linusborjesson.scorespeaker.ocr.CellDExtractor
import se.linusborjesson.scorespeaker.ocr.DigitTemplateMatcher
import se.linusborjesson.scorespeaker.processing.loadAlphabetFromDirectory
import java.io.File

/**
 * The production Cell D reader for tests: a [DigitTemplateMatcher] loaded
 * with the real-glyph shot-font alphabet from the corpus. Null when the
 * alphabet directory is missing/incomplete — tests then skip via
 * `assumeTrue`.
 */
fun glyphCellDExtractor(testDataDir: File): CellDExtractor? =
    DigitTemplateMatcher(minScore = 0.5)
        .takeIf { it.loadAlphabetFromDirectory(File(testDataDir, "glyphs/shot-font")) }
        ?.let { matcher -> CellDExtractor().apply { shotTemplateMatcher = matcher } }
