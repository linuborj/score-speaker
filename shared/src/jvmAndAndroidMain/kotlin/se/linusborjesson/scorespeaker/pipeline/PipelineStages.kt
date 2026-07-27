package se.linusborjesson.scorespeaker.pipeline

import org.opencv.core.Mat

/**
 * Stage 1: Screen detected in raw image.
 *
 * Successful detection of the SIUS display within the source image, with
 * corners identified.
 */
data class DetectedScreen(
    val source: ImageSource,
    val screenQuad: Quadrilateral,
    val confidence: Float,
    val detectionMethod: String = "unknown",
) {
    /**
     * Create a [RectifiedView] at a specific resolution.
     *
     * @param width Target width for rectified view.
     * @param height Target height (null = compute from [aspectRatio]).
     * @param aspectRatio Aspect ratio when [height] is null. Default 4:3.
     */
    fun rectify(
        width: Int,
        height: Int? = null,
        aspectRatio: Double = 4.0 / 3.0,
    ): RectifiedView {
        val targetHeight = height ?: (width / aspectRatio).toInt()
        val targetSize = Size2D(width, targetHeight)
        val transform = CoordinateTransform.create(screenQuad, targetSize)
        return RectifiedView(source, screenQuad, transform, targetSize)
    }

    /** [RectifiedView] preserving the detected screen's approximate width. */
    fun rectifyAtDetectedResolution(aspectRatio: Double = 4.0 / 3.0): RectifiedView {
        val detectedWidth = screenQuad.approximateWidth.toInt()
        return rectify(detectedWidth, (detectedWidth / aspectRatio).toInt())
    }

}

/**
 * Stage 2: Rectified view (perspective corrected).
 *
 * Provides Mat-level access to the rectified image and per-region warp.
 * The rectified Mat is computed lazily and cached.
 *
 * **Resource lifecycle**: holds a [CoordinateTransform] and possibly a Mat.
 * Call [close] when the view is no longer needed.
 */
class RectifiedView(
    val source: ImageSource,
    val screenQuad: Quadrilateral,
    val transform: CoordinateTransform,
    val targetSize: Size2D,
    /**
     * Whether [close] should release [transform]. Set true when this view
     * created the transform (e.g. ad-hoc rectification); set false when the
     * transform is owned by a longer-lived holder.
     */
    internal val ownsTransform: Boolean = true,
) : AutoCloseable {
    private var cachedRectifiedMat: Mat? = null

    /** Lazily warped full rectified image as a Mat. Cached; released by [close]. */
    val rectifiedMat: Mat
        get() = cachedRectifiedMat ?: transform.warpToRectified(source.mat)
            .also { cachedRectifiedMat = it }

    fun toSourceCoords(point: Point2D): Point2D = transform.toSource(point)
    fun toRectifiedCoords(point: Point2D): Point2D = transform.toRectified(point)
    fun toSourceCoords(region: Region): Region = transform.toSource(region)
    fun toRectifiedCoords(region: Region): Region = transform.toRectified(region)

    /**
     * Extract a rectified-coordinate region directly from the source Mat,
     * warped at the requested output size. Caller owns the returned Mat —
     * wrap in `.useMat { ... }` or call `.release()`.
     */
    fun extractRegionAsMat(rectifiedRegion: Region, outputSize: Size2D? = null): Mat {
        val outW = outputSize?.width ?: rectifiedRegion.width
        val outH = outputSize?.height ?: rectifiedRegion.height
        return transform.extractRectifiedRegion(source.mat, rectifiedRegion, outW, outH)
    }

    fun withSiusCells(): CellLayout = CellLayout(this, CellLayout.siusDisplayCells())

    override fun close() {
        cachedRectifiedMat?.release()
        cachedRectifiedMat = null
        if (ownsTransform) transform.close()
    }
}

/**
 * Stage 3: Cell layout — named regions defined as ratios relative to the
 * rectified screen.
 */
class CellLayout(
    val rectifiedView: RectifiedView,
    val cellDefinitions: List<CellDefinition>,
) {
    fun getCellRegion(cellName: String): Region? {
        val def = cellDefinitions.find { it.name == cellName } ?: return null
        return def.toRegion(rectifiedView.targetSize.width, rectifiedView.targetSize.height)
    }

    fun getAllCellRegions(): Map<String, Region> =
        cellDefinitions.associate { def ->
            def.name to def.toRegion(rectifiedView.targetSize.width, rectifiedView.targetSize.height)
        }

    /** Extract a cell as a Mat. Caller owns the returned Mat. */
    fun extractCellAsMat(cellName: String, outputSize: Size2D? = null): Mat? {
        val region = getCellRegion(cellName) ?: return null
        return rectifiedView.extractRegionAsMat(region, outputSize)
    }

    companion object {
        /** SIUS display cell definitions. Ratios are relative to the full rectified screen. */
        fun siusDisplayCells(): List<CellDefinition> = listOf(
            CellDefinition("A", 0.0, 0.0, 0.14232, 0.04121),
            CellDefinition("B", 0.74185, 0.02989, 0.25815, 0.14991),
            CellDefinition("C", 0.74185, 0.15851, 0.25815, 0.51721),
            CellDefinition("D", 0.74151, 0.65806, 0.25849, 0.15036),
            CellDefinition("E", 0.74117, 0.78940, 0.25883, 0.09149),
            CellDefinition("F", 0.74117, 0.86187, 0.25883, 0.13813),
            CellDefinition("G", 0.42052, 0.85824, 0.33662, 0.14176),
            CellDefinition("H", 0.0, 0.85824, 0.34443, 0.14176),
            // TARGET: the rings area on the left of the SIUS display, where
            // the green latest-shot marker lives. Constraining the green-dot
            // detector to this cell eliminates the status LED, the green
            // score text on the right panel, and any other competing greens.
            CellDefinition("TARGET", 0.0, 0.04121, 0.74117, 0.81703),
        )
    }
}

/**
 * Rectify, extract one named cell from the SIUS layout, run [block] over the
 * cell Mat, then release. Returns null if extraction fails. Convenience
 * wrapper for the rectify → extract → try/finally pattern.
 */
inline fun <T> DetectedScreen.useCell(
    cellName: String,
    block: (Mat) -> T?,
): T? = rectifyAtDetectedResolution().use { view ->
    val mat = view.withSiusCells().extractCellAsMat(cellName) ?: return@use null
    try {
        block(mat)
    } finally {
        mat.release()
    }
}
