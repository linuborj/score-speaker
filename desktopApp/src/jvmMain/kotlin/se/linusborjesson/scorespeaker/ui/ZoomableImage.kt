package se.linusborjesson.scorespeaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.MouseWheelZoom
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

/**
 * A marker to draw on the image, in image coordinates.
 */
data class ImageMarker(
    val x: Float,
    val y: Float,
    val color: Color = Color.Red,
    val label: String? = null,
)

/**
 * A cell overlay to draw on the image, with four corners in image coordinates.
 */
data class CellOverlay(
    val name: String,
    val corners: List<Offset>, // 4 corners: TL, TR, BR, BL
    val color: Color = Color.Cyan,
    val hasValue: Boolean = false
)

/**
 * Helper class to convert between layout coordinates and image pixel coordinates,
 * accounting for ContentScale.Fit and zoom/pan transforms.
 */
private class CoordinateConverter(
    val layoutSize: IntSize,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val imageAspect = imageWidth.toFloat() / imageHeight
    val layoutAspect = layoutSize.width.toFloat() / layoutSize.height

    val fitWidth: Float
    val fitHeight: Float
    val fitOffsetX: Float
    val fitOffsetY: Float
    val scaleToFit: Float

    val centerX = layoutSize.width / 2f
    val centerY = layoutSize.height / 2f

    init {
        if (imageAspect > layoutAspect) {
            fitWidth = layoutSize.width.toFloat()
            fitHeight = layoutSize.width / imageAspect
        } else {
            fitWidth = layoutSize.height * imageAspect
            fitHeight = layoutSize.height.toFloat()
        }
        fitOffsetX = (layoutSize.width - fitWidth) / 2f
        fitOffsetY = (layoutSize.height - fitHeight) / 2f
        scaleToFit = fitWidth / imageWidth
    }

    fun layoutToImage(layoutX: Float, layoutY: Float, scale: Float, offsetX: Float, offsetY: Float): Offset {
        val contentX = centerX + (layoutX - offsetX - centerX) / scale
        val contentY = centerY + (layoutY - offsetY - centerY) / scale
        val relX = contentX - fitOffsetX
        val relY = contentY - fitOffsetY
        val imageX = relX / scaleToFit
        val imageY = relY / scaleToFit
        return Offset(imageX, imageY)
    }

    fun imageToFitContent(imageX: Float, imageY: Float): Offset {
        val fitX = fitOffsetX + imageX * scaleToFit
        val fitY = fitOffsetY + imageY * scaleToFit
        return Offset(fitX, fitY)
    }

    fun imageToLayout(imageX: Float, imageY: Float, scale: Float, offsetX: Float, offsetY: Float): Offset {
        val contentX = fitOffsetX + imageX * scaleToFit
        val contentY = fitOffsetY + imageY * scaleToFit
        val layoutX = centerX + (contentX - centerX) * scale + offsetX
        val layoutY = centerY + (contentY - centerY) * scale + offsetY
        return Offset(layoutX, layoutY)
    }
}

/**
 * A zoomable image component with marker overlay support.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    markers: List<ImageMarker> = emptyList(),
    screenCorners: List<Offset>? = null,
    cellOverlays: List<CellOverlay> = emptyList(),
    gridLines: Any? = null, // Kept for API compatibility, ignored
    onImageClick: ((Offset) -> Unit)? = null,
    onMarkerDrag: ((index: Int, newPosition: Offset) -> Unit)? = null,
    onGridLineDrag: ((lineId: String, newPosition: Double) -> Unit)? = null // Kept for API compatibility, ignored
) {
    var draggingMarkerIndex by remember { mutableStateOf(-1) }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    val currentMarkers by rememberUpdatedState(markers)

    val zoomState = rememberZoomState(
        contentSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
    )

    val converter = remember(layoutSize, bitmap.width, bitmap.height) {
        if (layoutSize.width > 0 && layoutSize.height > 0) {
            CoordinateConverter(layoutSize, bitmap.width, bitmap.height)
        } else null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.DarkGray)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { layoutSize = it }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.first()
                    val scrollDelta = change.scrollDelta.y
                    val zoomFactor = 1f - scrollDelta * 0.1f
                    val newScale = (zoomState.scale * zoomFactor).coerceIn(1f, 5f)
                    scope.launch {
                        zoomState.changeScale(newScale, change.position)
                    }
                    change.consume()
                }
                .let { mod ->
                    if (onMarkerDrag != null) {
                        mod.pointerInput(onMarkerDrag) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    converter?.let { conv ->
                                        val hitRadius = 30f
                                        draggingMarkerIndex = currentMarkers.indexOfFirst { marker ->
                                            val markerLayout = conv.imageToLayout(
                                                marker.x, marker.y,
                                                zoomState.scale, zoomState.offsetX, zoomState.offsetY
                                            )
                                            val dx = offset.x - markerLayout.x
                                            val dy = offset.y - markerLayout.y
                                            dx * dx + dy * dy < hitRadius * hitRadius
                                        }
                                    } ?: run { draggingMarkerIndex = -1 }
                                },
                                onDrag = { change, _ ->
                                    if (draggingMarkerIndex >= 0) {
                                        change.consume()
                                        converter?.let { conv ->
                                            val imageCoords = conv.layoutToImage(
                                                change.position.x, change.position.y,
                                                zoomState.scale, zoomState.offsetX, zoomState.offsetY
                                            )
                                            val clampedX = imageCoords.x.coerceIn(0f, bitmap.width.toFloat())
                                            val clampedY = imageCoords.y.coerceIn(0f, bitmap.height.toFloat())
                                            onMarkerDrag(draggingMarkerIndex, Offset(clampedX, clampedY))
                                        }
                                    }
                                },
                                onDragEnd = { draggingMarkerIndex = -1 },
                                onDragCancel = { draggingMarkerIndex = -1 }
                            )
                        }
                    } else {
                        mod
                    }
                }
                .zoomable(
                    zoomState = zoomState,
                    mouseWheelZoom = MouseWheelZoom.Disabled,
                    zoomEnabled = onMarkerDrag == null,
                    onTap = if (onImageClick != null && onMarkerDrag == null) {
                        { position ->
                            converter?.let { conv ->
                                val imageCoords = conv.layoutToImage(
                                    position.x, position.y,
                                    zoomState.scale, zoomState.offsetX, zoomState.offsetY
                                )
                                if (imageCoords.x >= 0 && imageCoords.x < bitmap.width &&
                                    imageCoords.y >= 0 && imageCoords.y < bitmap.height
                                ) {
                                    onImageClick(imageCoords)
                                }
                            }
                        }
                    } else null
                )
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            // Draw cell overlays
            if (cellOverlays.isNotEmpty() && converter != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 2f

                    for (cell in cellOverlays) {
                        if (cell.corners.size == 4) {
                            val fitCorners = cell.corners.map {
                                converter.imageToFitContent(it.x, it.y)
                            }

                            // Draw cell boundary
                            val cellColor = if (cell.hasValue) Color.Green else cell.color
                            for (i in 0 until 4) {
                                val next = (i + 1) % 4
                                drawLine(
                                    color = cellColor.copy(alpha = 0.7f),
                                    start = fitCorners[i],
                                    end = fitCorners[next],
                                    strokeWidth = strokeWidth
                                )
                            }

                            // Draw cell label background at center
                            val centerX = fitCorners.map { it.x }.average().toFloat()
                            val centerY = fitCorners.map { it.y }.average().toFloat()

                            val labelRadius = 14f
                            // Background circle
                            drawCircle(
                                color = if (cell.hasValue) Color.Green else Color.Cyan,
                                radius = labelRadius,
                                center = Offset(centerX, centerY)
                            )
                            // Border
                            drawCircle(
                                color = Color.White,
                                radius = labelRadius,
                                center = Offset(centerX, centerY),
                                style = Stroke(width = 2f)
                            )
                        }
                    }
                }

                // Draw cell labels using native text (Box positioning)
                cellOverlays.forEach { cell ->
                    if (cell.corners.size == 4) {
                        val fitCorners = cell.corners.map {
                            converter.imageToFitContent(it.x, it.y)
                        }
                        val centerX = fitCorners.map { it.x }.average().toFloat()
                        val centerY = fitCorners.map { it.y }.average().toFloat()

                        Box(
                            modifier = Modifier
                                .absoluteOffset(x = centerX.dp - 6.dp, y = centerY.dp - 10.dp)
                        ) {
                            androidx.compose.material3.Text(
                                text = cell.name,
                                color = Color.Black,
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            // Draw markers and connecting lines
            if (markers.isNotEmpty() && converter != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val markerRadius = 8f
                    val strokeWidth = 2f

                    // Draw connecting lines between markers (forming quadrilateral)
                    if (markers.size > 1) {
                        for (i in markers.indices) {
                            val next = (i + 1) % markers.size
                            val start = converter.imageToFitContent(markers[i].x, markers[i].y)
                            val end = converter.imageToFitContent(markers[next].x, markers[next].y)
                            drawLine(
                                color = Color.Yellow.copy(alpha = 0.7f),
                                start = start,
                                end = end,
                                strokeWidth = strokeWidth
                            )
                        }
                    }

                    // Draw markers on top
                    for (marker in markers) {
                        val pos = converter.imageToFitContent(marker.x, marker.y)
                        drawCircle(
                            color = marker.color,
                            radius = markerRadius,
                            center = pos
                        )
                        drawCircle(
                            color = Color.White,
                            radius = markerRadius,
                            center = pos,
                            style = Stroke(width = strokeWidth)
                        )
                    }
                }
            }

        }
    }
}
