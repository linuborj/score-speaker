package se.linusborjesson.scorespeaker.pipeline

import java.awt.image.BufferedImage

/**
 * Desktop BufferedImage convenience extensions on [RectifiedView] and
 * [CellLayout]. The core types are Mat-only and live in shared
 * (`jvmAndAndroidMain`); this file provides the AWT-side adapters that
 * desktop tooling (labeling GUI, headless CLI, JUnit smoke tests) finds
 * handy.
 */

/** Extract a region as a [BufferedImage] (Mat → AWT, releases the Mat). */
fun RectifiedView.extractRegionAsImage(
    rectifiedRegion: Region,
    outputSize: Size2D? = null,
): BufferedImage = extractRegionAsMat(rectifiedRegion, outputSize)
    .useMat(ImageBridge::toBufferedImage)

/** Extract a named cell as a [BufferedImage], or null if the cell name isn't in the layout. */
fun CellLayout.extractCell(
    cellName: String,
    outputSize: Size2D? = null,
): BufferedImage? {
    val region = getCellRegion(cellName) ?: return null
    return rectifiedView.extractRegionAsImage(region, outputSize)
}
