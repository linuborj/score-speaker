package se.linusborjesson.scorespeaker

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import se.linusborjesson.scorespeaker.ui.CvOverlay
import se.linusborjesson.scorespeaker.ui.DebugPanelState
import se.linusborjesson.scorespeaker.ui.LiveScreen
import se.linusborjesson.scorespeaker.ui.LiveScreenState

/**
 * Android live screen — a thin wrapper over the shared [LiveScreen]. Maps the
 * Android [LiveUiState] to the platform-neutral [LiveScreenState] and injects
 * a CameraX [PreviewView] as the camera surface. [onPreviewReady] fires once
 * that view exists so the activity can bind CameraX.
 */
@Composable
fun CameraScreen(
    state: LiveUiState,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPreviewReady: (PreviewView) -> Unit,
    onCaptureClick: () -> Unit,
    onZoomGesture: (Float) -> Unit,
) {
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LiveScreen(
        state = state.toScreenState(),
        onHistoryClick = onHistoryClick,
        onSettingsClick = onSettingsClick,
        onCaptureClick = onCaptureClick,
        onZoomGesture = onZoomGesture,
        camera = {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).also { previewView = it } },
                modifier = Modifier.fillMaxSize(),
            )
        },
    )

    LaunchedEffect(previewView) { previewView?.let(onPreviewReady) }
}

private fun LiveUiState.toScreenState() = LiveScreenState(
    hasPermission = hasPermission,
    cameraUnavailable = cameraUnavailable,
    locked = locked,
    overlay = overlay?.let {
        CvOverlay(
            it.corners, it.analyzerWidth, it.analyzerHeight, it.method,
            it.shotDot, it.targetCenter, it.dShotRegion,
        )
    },
    shotNumber = shotNumber,
    score = score,
    offsetXRings = offsetXRings,
    // image y is +down; the screen displays +up, so flip.
    offsetYRings = offsetYRings?.let { -it },
    debug = debug?.let {
        DebugPanelState(
            detectionLabel = it.detectionLabel,
            fps = it.fps,
            cacheLine = it.cacheLine,
            dCell = it.dCell?.asImageBitmap(),
            dText = it.dText,
            dShotProcessed = it.dShotProcessed?.asImageBitmap(),
            dConfidence = it.dConfidence,
        )
    },
)
