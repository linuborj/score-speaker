package se.linusborjesson.scorespeaker.pipeline

import org.opencv.core.Mat
import se.linusborjesson.scorespeaker.cells.CellValue

/**
 * Reads a single cell's region (as a Mat in rectified coordinates) and returns
 * a typed value, or null if the cell can't be interpreted.
 *
 * Cell-specific implementations (score, status, coordinates) live alongside
 * their interpretation logic.
 */
fun interface CellExtractor {
    fun extract(cellMat: Mat): CellValue?
}
