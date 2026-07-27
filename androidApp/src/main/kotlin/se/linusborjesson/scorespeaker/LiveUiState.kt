package se.linusborjesson.scorespeaker

/**
 * Everything the live screen renders, in one snapshot. Built on the main
 * thread from the latest [FrameResult] + the [se.linusborjesson.scorespeaker.pipeline.ShotTracker]
 * log, and updated field-by-field via [copy] from the activity's various
 * callbacks (frame, permission).
 */
data class LiveUiState(
    val hasPermission: Boolean = false,
    val cameraUnavailable: Boolean = false,
    val locked: Boolean = false,
    val overlay: OverlayQuad? = null,
    val shotNumber: Int? = null,
    val score: String? = null,
    // Last shot's offset in ring units, image convention (+x right, +y down).
    val offsetXRings: Double? = null,
    val offsetYRings: Double? = null,
    // Non-null only while the debug-overlay setting is on.
    val debug: DebugFrame? = null,
)
