package se.linusborjesson.scorespeaker.pipeline

import kotlin.math.sqrt

/** A 2D point with double precision. */
data class Point2D(val x: Double, val y: Double) {
    operator fun plus(other: Point2D) = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D) = Point2D(x - other.x, y - other.y)
    operator fun times(scale: Double) = Point2D(x * scale, y * scale)

    fun distanceTo(other: Point2D): Double {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * A rectangular region with integer coordinates. Coordinates are always
 * relative to some reference image/coordinate system.
 */
data class Region(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun scale(factor: Double) = Region(
        x = (x * factor).toInt(),
        y = (y * factor).toInt(),
        width = (width * factor).toInt(),
        height = (height * factor).toInt(),
    )

    fun toQuadrilateral() = Quadrilateral(
        topLeft = Point2D(x.toDouble(), y.toDouble()),
        topRight = Point2D(right.toDouble(), y.toDouble()),
        bottomRight = Point2D(right.toDouble(), bottom.toDouble()),
        bottomLeft = Point2D(x.toDouble(), bottom.toDouble()),
    )
}

/**
 * A quadrilateral defined by four corners.
 * Order: top-left, top-right, bottom-right, bottom-left (clockwise from TL).
 */
data class Quadrilateral(
    val topLeft: Point2D,
    val topRight: Point2D,
    val bottomRight: Point2D,
    val bottomLeft: Point2D,
) {
    val corners: List<Point2D> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Approximate width (average of top and bottom edges). */
    val approximateWidth: Double get() =
        (topLeft.distanceTo(topRight) + bottomLeft.distanceTo(bottomRight)) / 2

    fun scale(factor: Double) = Quadrilateral(
        topLeft = topLeft * factor,
        topRight = topRight * factor,
        bottomRight = bottomRight * factor,
        bottomLeft = bottomLeft * factor,
    )

    /** Convert to a list of (x, y) pairs in TL, TR, BR, BL order. Counterpart to [fromPairs]. */
    fun toPairs(): List<Pair<Double, Double>> = corners.map { it.x to it.y }

    companion object {
        /** Create a quadrilateral from a list of corner pairs (x, y). Order: TL, TR, BR, BL. */
        fun fromPairs(corners: List<Pair<Double, Double>>): Quadrilateral {
            require(corners.size == 4) { "Need exactly 4 corners" }
            return Quadrilateral(
                topLeft = Point2D(corners[0].first, corners[0].second),
                topRight = Point2D(corners[1].first, corners[1].second),
                bottomRight = Point2D(corners[2].first, corners[2].second),
                bottomLeft = Point2D(corners[3].first, corners[3].second),
            )
        }

        /** Create a rectangular quadrilateral from dimensions. */
        fun rectangle(width: Int, height: Int) = Quadrilateral(
            topLeft = Point2D(0.0, 0.0),
            topRight = Point2D(width.toDouble(), 0.0),
            bottomRight = Point2D(width.toDouble(), height.toDouble()),
            bottomLeft = Point2D(0.0, height.toDouble()),
        )
    }
}

/**
 * A cell definition using ratios (0.0-1.0) relative to the screen bounds.
 * Resolution-independent.
 */
data class CellDefinition(
    val name: String,
    val xRatio: Double,
    val yRatio: Double,
    val widthRatio: Double,
    val heightRatio: Double,
) {
    fun toRegion(targetWidth: Int, targetHeight: Int) = Region(
        x = (xRatio * targetWidth).toInt(),
        y = (yRatio * targetHeight).toInt(),
        width = (widthRatio * targetWidth).toInt().coerceAtLeast(1),
        height = (heightRatio * targetHeight).toInt().coerceAtLeast(1),
    )
}
