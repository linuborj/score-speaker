package se.linusborjesson.scorespeaker.pipeline

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Encapsulates a perspective transform between a source quadrilateral (in source
 * image coordinates) and a rectified target rectangle of [targetSize].
 *
 * Holds both the forward and inverse 3×3 homographies so both point/region
 * mappings and image warps can be done without recomputing.
 *
 * **Resource lifecycle**: holds two OpenCV Mats. Call [close] when done. Cheap
 * to recreate — usually one per detected screen.
 */
class CoordinateTransform private constructor(
    private val forwardMatrix: Mat,
    private val inverseMatrix: Mat,
    val sourceQuad: Quadrilateral,
    val targetSize: Size2D,
) : AutoCloseable {

    /** Source coords → rectified coords. */
    fun toRectified(point: Point2D): Point2D = transformPoint(point, forwardMatrix)

    /** Rectified coords → source coords. */
    fun toSource(point: Point2D): Point2D = transformPoint(point, inverseMatrix)

    /**
     * Transform a region's axis-aligned bounding box. Note this is exact only
     * when the source quad is axis-aligned in the source image; for tilted
     * sources, prefer mapping all 4 corners explicitly.
     */
    fun toRectified(region: Region): Region = transformRegion(region, ::toRectified)
    fun toSource(region: Region): Region = transformRegion(region, ::toSource)

    fun toRectified(quad: Quadrilateral): Quadrilateral = Quadrilateral(
        toRectified(quad.topLeft), toRectified(quad.topRight),
        toRectified(quad.bottomRight), toRectified(quad.bottomLeft),
    )

    fun toSource(quad: Quadrilateral): Quadrilateral = Quadrilateral(
        toSource(quad.topLeft), toSource(quad.topRight),
        toSource(quad.bottomRight), toSource(quad.bottomLeft),
    )

    /**
     * Warp [source] (a full-source image Mat) into a rectified Mat at [targetSize].
     * Caller owns the returned Mat.
     */
    fun warpToRectified(source: Mat): Mat {
        val dst = Mat()
        Imgproc.warpPerspective(
            source, dst, forwardMatrix,
            Size(targetSize.width.toDouble(), targetSize.height.toDouble()),
        )
        return dst
    }

    /**
     * Extract a rectified-coordinate region from [source], directly warping at
     * the requested output size (no intermediate full rectification). Caller
     * owns the returned Mat.
     */
    fun extractRectifiedRegion(
        source: Mat,
        rectifiedRegion: Region,
        outputWidth: Int? = null,
        outputHeight: Int? = null,
    ): Mat {
        val outW = outputWidth ?: rectifiedRegion.width
        val outH = outputHeight ?: rectifiedRegion.height

        // Source quad: the 4 corners of the rectified region mapped back to source space.
        val srcQuad = Quadrilateral(
            toSource(Point2D(rectifiedRegion.x.toDouble(), rectifiedRegion.y.toDouble())),
            toSource(Point2D(rectifiedRegion.right.toDouble(), rectifiedRegion.y.toDouble())),
            toSource(Point2D(rectifiedRegion.right.toDouble(), rectifiedRegion.bottom.toDouble())),
            toSource(Point2D(rectifiedRegion.x.toDouble(), rectifiedRegion.bottom.toDouble())),
        )
        return create(srcQuad, Size2D(outW, outH)).use { tx ->
            tx.warpToRectified(source)
        }
    }

    override fun close() {
        forwardMatrix.release()
        inverseMatrix.release()
    }

    private fun transformRegion(region: Region, transform: (Point2D) -> Point2D): Region {
        val tl = transform(Point2D(region.x.toDouble(), region.y.toDouble()))
        val br = transform(Point2D(region.right.toDouble(), region.bottom.toDouble()))
        return Region(
            x = tl.x.toInt(),
            y = tl.y.toInt(),
            width = (br.x - tl.x).toInt().coerceAtLeast(1),
            height = (br.y - tl.y).toInt().coerceAtLeast(1),
        )
    }

    private fun transformPoint(point: Point2D, matrix: Mat): Point2D {
        val src = MatOfPoint2f(Point(point.x, point.y))
        val dst = MatOfPoint2f()
        try {
            Core.perspectiveTransform(src, dst, matrix)
            val r = dst.toArray()[0]
            return Point2D(r.x, r.y)
        } finally {
            src.release()
            dst.release()
        }
    }

    companion object {
        @Volatile private var openCvLoaded = false

        /** Load OpenCV native libraries idempotently (delegates per platform). */
        fun ensureOpenCv() {
            if (openCvLoaded) return
            synchronized(this) {
                if (!openCvLoaded) {
                    loadOpenCvNative()
                    openCvLoaded = true
                }
            }
        }

        /** Create a transform mapping [sourceQuad] in source-image coords to a [targetSize] rectangle. */
        fun create(sourceQuad: Quadrilateral, targetSize: Size2D): CoordinateTransform {
            ensureOpenCv()
            val src = MatOfPoint2f(
                Point(sourceQuad.topLeft.x, sourceQuad.topLeft.y),
                Point(sourceQuad.topRight.x, sourceQuad.topRight.y),
                Point(sourceQuad.bottomRight.x, sourceQuad.bottomRight.y),
                Point(sourceQuad.bottomLeft.x, sourceQuad.bottomLeft.y),
            )
            val dst = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(targetSize.width.toDouble(), 0.0),
                Point(targetSize.width.toDouble(), targetSize.height.toDouble()),
                Point(0.0, targetSize.height.toDouble()),
            )
            try {
                val forward = Imgproc.getPerspectiveTransform(src, dst)
                val inverse = Mat()
                Core.invert(forward, inverse)
                return CoordinateTransform(forward, inverse, sourceQuad, targetSize)
            } finally {
                src.release()
                dst.release()
            }
        }
    }
}

/** Simple integer 2D size. */
data class Size2D(val width: Int, val height: Int) {
    val aspectRatio: Double get() = width.toDouble() / height

    fun scale(factor: Double) = Size2D(
        width = (width * factor).toInt(),
        height = (height * factor).toInt(),
    )
}

/** Apply [block] to this transform and release its native Mats when done. */
inline fun <T> CoordinateTransform.use(block: (CoordinateTransform) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
