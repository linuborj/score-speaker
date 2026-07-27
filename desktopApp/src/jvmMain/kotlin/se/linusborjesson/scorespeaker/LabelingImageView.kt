package se.linusborjesson.scorespeaker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.ui.ImageMarker
import se.linusborjesson.scorespeaker.ui.ZoomableImage

/**
 * Main image area: the zoomable source image with corner markers, cell
 * overlays in EditValues mode, and mode-dependent click/drag behaviour.
 */
@Composable
internal fun LabelingImageView(
    modifier: Modifier = Modifier,
    errorMessage: String?,
    imageBitmap: ImageBitmap?,
    isLoading: Boolean,
    labelingMode: LabelingMode,
    corners: List<Offset>,
    onCornersChange: (List<Offset>) -> Unit,
    expectedValues: Map<String, CellValue>,
) {
    Box(
        modifier = modifier.background(Color.DarkGray),
        contentAlignment = Alignment.Center
    ) {
        when {
            errorMessage != null -> Text(
                errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge
            )
            imageBitmap != null -> {
                val markers = corners.mapIndexed { index, offset ->
                    ImageMarker(
                        x = offset.x,
                        y = offset.y,
                        color = when (index) {
                            0 -> Color.Red
                            1 -> Color.Green
                            2 -> Color.Blue
                            else -> Color.Yellow
                        },
                        label = CORNER_LABELS.getOrNull(index)
                    )
                }

                // Compute cell overlays when in EditValues mode
                val cellOverlays = if (labelingMode == LabelingMode.EditValues && corners.size == 4) {
                    computeCellOverlays(corners, expectedValues)
                } else {
                    emptyList()
                }

                ZoomableImage(
                    bitmap = imageBitmap,
                    contentDescription = "Test image",
                    modifier = Modifier.fillMaxSize(),
                    markers = if (labelingMode != LabelingMode.EditValues) markers else emptyList(),
                    screenCorners = if (corners.size == 4 && labelingMode != LabelingMode.EditValues) corners else null,
                    cellOverlays = cellOverlays,
                    gridLines = null,
                    onImageClick = when (labelingMode) {
                        LabelingMode.MarkCorners -> if (corners.size < 4) {
                            { imageCoords -> onCornersChange(corners + imageCoords) }
                        } else null
                        else -> null
                    },
                    onMarkerDrag = if (labelingMode == LabelingMode.MarkCorners) {
                        { index, newPosition ->
                            onCornersChange(corners.toMutableList().apply { set(index, newPosition) })
                        }
                    } else null,
                    onGridLineDrag = null
                )
            }
            else -> Text(
                "No image selected.\nSelect an item or import a new image.",
                color = Color.LightGray,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // Loading overlay (shown on top of image)
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}
