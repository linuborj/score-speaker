package se.linusborjesson.scorespeaker

import androidx.compose.ui.geometry.Offset
import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.pipeline.CellLayout
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.pipeline.ImageBridge
import se.linusborjesson.scorespeaker.pipeline.Point2D
import se.linusborjesson.scorespeaker.pipeline.Quadrilateral
import se.linusborjesson.scorespeaker.pipeline.ScreenDetector
import se.linusborjesson.scorespeaker.pipeline.Size2D
import se.linusborjesson.scorespeaker.processing.MultiScaleMatcher
import se.linusborjesson.scorespeaker.ui.CellOverlay
import java.io.File
import javax.imageio.ImageIO

/**
 * The cells worth annotating: only D — the shot reader's ground truth.
 * No other cell's expectation is consumed by any test or tool. (Cell G
 * is the mean point of impact over the last 5 shots, not a per-shot
 * value, so it can't validate per-shot scoring.)
 */
internal val LABEL_CELLS = setOf("D")

/** Corner click/marker order — must match [ScreenCorners] field order. */
internal val CORNER_LABELS = listOf("Top-Left", "Top-Right", "Bottom-Right", "Bottom-Left")

internal fun createDetector(testDataDir: File): ScreenDetector? {
    val templateFile = File(testDataDir, "masked-template.png")
    if (!templateFile.exists()) return null

    return try {
        val templateImage = ImageIO.read(templateFile)
        val detector = MultiScaleMatcher(
            scales = listOf(0.3, 0.5, 0.7, 1.0),
            maxFeatures = 500,
            minInliers = 8,
            maxInputWidth = 2500,
        )
        detector.loadTemplate(ImageBridge.toMat(templateImage))
        detector
    } catch (e: Exception) {
        println("Warning: Could not load template for auto-detection: ${e.message}")
        null
    }
}

/**
 * Compute cell overlay positions by transforming normalized cell coordinates
 * to image coordinates using perspective transform.
 */
internal fun computeCellOverlays(
    corners: List<Offset>,
    expectedValues: Map<String, CellValue>,
): List<CellOverlay> {
    if (corners.size != 4) return emptyList()

    val srcCorners = corners.map { Point2D(it.x.toDouble(), it.y.toDouble()) }

    // Build a transform that maps the screen quadrilateral (in image-pixel
    // space) to the unit square (normalized cell space). toSource() then
    // maps [0,1] cell corners into image pixels.
    val screenQuad = Quadrilateral(
        topLeft = srcCorners[0],
        topRight = srcCorners[1],
        bottomRight = srcCorners[2],
        bottomLeft = srcCorners[3],
    )
    val transform = CoordinateTransform.create(screenQuad, Size2D(1, 1))
    return try {
        CellLayout.siusDisplayCells()
            .filter { it.name in LABEL_CELLS }
            .map { cellDef ->
                val cellNormalized = listOf(
                    Point2D(cellDef.xRatio, cellDef.yRatio),
                    Point2D(cellDef.xRatio + cellDef.widthRatio, cellDef.yRatio),
                    Point2D(cellDef.xRatio + cellDef.widthRatio, cellDef.yRatio + cellDef.heightRatio),
                    Point2D(cellDef.xRatio, cellDef.yRatio + cellDef.heightRatio),
                )
                CellOverlay(
                    name = cellDef.name,
                    corners = cellNormalized.map { p ->
                        val img = transform.toSource(p)
                        Offset(img.x.toFloat(), img.y.toFloat())
                    },
                    hasValue = expectedValues.containsKey(cellDef.name),
                )
            }
    } finally {
        transform.close()
    }
}
