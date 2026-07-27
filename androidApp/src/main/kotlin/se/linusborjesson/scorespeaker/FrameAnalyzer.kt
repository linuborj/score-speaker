package se.linusborjesson.scorespeaker

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.ocr.CellDExtractor
import se.linusborjesson.scorespeaker.pipeline.CachedCellExtractor
import se.linusborjesson.scorespeaker.pipeline.Change
import se.linusborjesson.scorespeaker.pipeline.ChangeTracker
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.LockingScreenDetector
import se.linusborjesson.scorespeaker.pipeline.Point2D
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.pipeline.Reading
import se.linusborjesson.scorespeaker.pipeline.ShotProcessOutcome
import se.linusborjesson.scorespeaker.pipeline.ShotTracker
import java.util.Locale
import se.linusborjesson.scorespeaker.processing.GreenDotDetector
import se.linusborjesson.scorespeaker.processing.MissBadgeDetector
import se.linusborjesson.scorespeaker.processing.MissDetection
import se.linusborjesson.scorespeaker.processing.ShotMeasurement
import se.linusborjesson.scorespeaker.processing.TargetScoring
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Snapshot of the detected screen quadrilateral in analyzer-frame pixel
 * coordinates, plus the analyzer frame's dimensions so the overlay Composable
 * can map into view pixels. [method] ("refined" vs full detection) is shown
 * in the debug HUD.
 */
data class OverlayQuad(
    val corners: List<Pair<Float, Float>>,
    val analyzerWidth: Int,
    val analyzerHeight: Int,
    val method: String,
    /** Detected green-dot position in analyzer-frame px (debug overlay only). */
    val shotDot: Pair<Float, Float>? = null,
    /** Estimated target centre in analyzer-frame px (default overlay). */
    val targetCenter: Pair<Float, Float>? = null,
    /** Cell D shot-OCR sub-region as a frame-space quad (debug overlay only). */
    val dShotRegion: List<Pair<Float, Float>>? = null,
)

/**
 * Pipeline internals for the live-screen debug panel, built only when the
 * debug-overlay setting is on: the actual Cell D crop OCR ran on (so misreads
 * can be traced to bad crops vs bad recognition) plus what was read from it.
 */
data class DebugFrame(
    val detectionLabel: String,
    val fps: Double,
    val cacheLine: String,
    val dCell: Bitmap?,
    val dText: String?,
    /** The reduced shot crop exactly as the glyph matcher sees it. */
    val dShotProcessed: Bitmap? = null,
    /** Formatted glyph-confidence line, e.g. "glyph 62%". */
    val dConfidence: String? = null,
)

/**
 * Per-frame output produced by [FrameAnalyzer]. The caller owns dispatching —
 * typically forwarded to the main thread and pushed into Compose state.
 */
data class FrameResult(
    val readingText: String,
    val fps: Double,
    val cacheLine: String,
    val detectionLabel: String,
    val confidence: Float?,
    val overlay: OverlayQuad?,
    val changes: List<Change>?,
    val shotOutcome: ShotProcessOutcome?,
    val debug: DebugFrame?,
)

/**
 * Per-frame pipeline: detect → rectify → extract D + TARGET → track → emit a
 * [FrameResult]. Runs on the camera analysis worker thread; [onCapture] and
 * [onFrameProcessed] are invoked on that same thread.
 *
 * On detection failure the tracker is NOT fed — a momentary miss between good
 * frames doesn't emit a spurious "score went null" change.
 */
class FrameAnalyzer(
    private val detector: LockingScreenDetector,
    private val cellDExtractor: CachedCellExtractor,
    private val greenDotDetector: GreenDotDetector,
    private val targetScoring: TargetScoring,
    private val tracker: ChangeTracker,
    private val shotTracker: ShotTracker,
    /**
     * Off-target badge detector. Null disables miss reporting entirely —
     * the pre-existing behaviour, where a missed shot is simply never
     * attributed.
     */
    private val missBadgeDetector: MissBadgeDetector? = null,
    /** Read per frame; when false misses are not looked for. */
    private val announceMissesEnabled: () -> Boolean = { true },
    private val pendingCapture: AtomicBoolean,
    private val onCapture: (CaptureInputs) -> Unit,
    private val onFrameProcessed: (FrameResult) -> Unit,
    /** Read per frame; when true the [FrameResult] carries a [DebugFrame]. */
    private val debugEnabled: () -> Boolean = { false },
    /**
     * Produces the reduced shot crop from a D-cell Mat for the debug panel;
     * the caller of the lambda owns the returned Mat. Goes through the inner
     * [se.linusborjesson.scorespeaker.ocr.CellDExtractor] directly because
     * the cache wrapper skips it on repeat frames.
     */
    private val debugProcessedCells: ((Mat) -> Mat)? = null,
    /** Formatted glyph-confidence line for the debug panel. */
    private val debugOcrConfidence: (() -> String?)? = null,
    /** Read when a shot is confirmed; when true that frame is captured. */
    private val autoCaptureEnabled: () -> Boolean = { false },
) : ImageAnalysis.Analyzer {

    private var lastFrameNs = 0L

    override fun analyze(image: ImageProxy) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.0 else (now - lastFrameNs) / 1e9
        lastFrameNs = now

        try {
            // Analyzer frames arrive sensor-oriented; rotate them upright so
            // detection sees the display the way the preview shows it and the
            // overlay's analyzer→view mapping holds in portrait too. In
            // landscape (the common tripod setup) rotationDegrees is 0 and
            // this is free.
            val raw = image.toBitmap()
            val rotationDegrees = image.imageInfo.rotationDegrees
            val bitmap = if (rotationDegrees != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else raw
            val rgba = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()

            var dValue: CellValue? = null
            var debugDCell: Bitmap? = null
            var debugDShotProc: Bitmap? = null
            var measurement: ShotMeasurement? = null
            var miss: MissDetection? = null
            var changes: List<Change>? = null
            var shotOutcome: ShotProcessOutcome? = null
            var detectionLabel = "No detect ✗"
            var confidence: Float? = null
            var nextOverlay: OverlayQuad? = null

            // ImageSource takes ownership of bgr — closing releases the Mat.
            // Keep bgr alive until after capture by deferring src.close().
            val src = ImageSource(bgr)
            try {
                val detected = detector.detect(src)
                if (detected != null) {
                    // A full detection (anything but a warm-frame refine) can
                    // move the rectification while the TARGET cell rounds to
                    // the same size — the measured-geometry cache must not
                    // survive that.
                    if (detected.detectionMethod != "refined") targetScoring.invalidateGeometry()
                    val verb = if (detected.detectionMethod == "refined") "Refined" else "Detected"
                    detectionLabel = "$verb ✓ (${"%.2f".format(detected.confidence)})"
                    confidence = detected.confidence
                    nextOverlay = OverlayQuad(
                        corners = detected.screenQuad.toPairs().map { (x, y) ->
                            x.toFloat() to y.toFloat()
                        },
                        analyzerWidth = bgr.cols(),
                        analyzerHeight = bgr.rows(),
                        method = detected.detectionMethod,
                    )
                    detected.rectifyAtDetectedResolution().use { view ->
                        val layout = view.withSiusCells()
                        val dMat = layout.extractCellAsMat("D")
                        val targetMat = layout.extractCellAsMat("TARGET")
                        try {
                            dValue = dMat?.let { cellDExtractor.extract(it) }
                            if (debugEnabled()) {
                                debugDCell = dMat?.let { cellToBitmap(it) }
                                dMat?.let { m ->
                                    debugProcessedCells?.invoke(m)?.let { shot ->
                                        debugDShotProc = cellToBitmap(shot)
                                        shot.release()
                                    }
                                }
                            }
                            var dotInCell: Point2D? = null
                            measurement = targetMat?.let { tm ->
                                val dot = greenDotDetector.detect(tm) ?: return@let null
                                dotInCell = Point2D(dot.centroidX, dot.centroidY)
                                targetScoring.measure(tm, dotInCell!!)
                            }
                            // Map overlay markers from cell px back to
                            // analyzer-frame px — a visual end-to-end check
                            // of the geometry chain (cell extraction is 1:1
                            // with the rectified region, so cell px + region
                            // origin = rectified px).
                            fun toFrame(cellName: String, p: Point2D): Pair<Float, Float>? {
                                val r = layout.getCellRegion(cellName) ?: return null
                                val src = view.toSourceCoords(Point2D(r.x + p.x, r.y + p.y))
                                return src.x.toFloat() to src.y.toFloat()
                            }

                            // The centre cross rides the default overlay,
                            // like the corner brackets — it's setup feedback
                            // (is the geometry pointing where the helper
                            // thinks?), not a debug detail. centerFor() is
                            // cheap on repeat frames via the geometry cache.
                            val centerInCell = targetMat?.let { targetScoring.centerFor(it) }
                            nextOverlay = nextOverlay?.copy(
                                targetCenter = centerInCell?.let { toFrame("TARGET", it) },
                            )

                            if (debugEnabled()) {
                                // A rectified-space rectangle is a quad in
                                // frame space — map all four corners.
                                fun rectToFrame(cellName: String, r: org.opencv.core.Rect): List<Pair<Float, Float>>? =
                                    listOf(
                                        Point2D(r.x.toDouble(), r.y.toDouble()),
                                        Point2D((r.x + r.width).toDouble(), r.y.toDouble()),
                                        Point2D((r.x + r.width).toDouble(), (r.y + r.height).toDouble()),
                                        Point2D(r.x.toDouble(), (r.y + r.height).toDouble()),
                                    ).map { toFrame(cellName, it) ?: return null }

                                nextOverlay = nextOverlay?.copy(
                                    shotDot = dotInCell?.let { toFrame("TARGET", it) },
                                    dShotRegion = dMat?.let { rectToFrame("D", CellDExtractor.shotRect(it.cols(), it.rows())) },
                                )
                            }

                            // No marker on the face, but the reader has a
                            // shot number: the display may have placed the
                            // shot off-target, which it draws as an edge
                            // badge rather than omitting. Only looked for
                            // when there is no measurement — a scored shot
                            // is never also a miss.
                            if (measurement == null && announceMissesEnabled()) {
                                val expected = (dValue as? ScoreShotValue)?.shot?.toIntOrNull()
                                if (expected != null && targetMat != null) {
                                    miss = missBadgeDetector?.detect(targetMat, expected)
                                }
                            }

                            // Score from the marker, shot number from the
                            // reader: replace the read score with the
                            // measured one — the score is only ever
                            // derived from the green dot; the glyph read
                            // carries shot number and practice flag. No
                            // measurement → no attribution yet (the marker
                            // appears with the shot; a detection gap just
                            // delays), unless the shot was a confirmed
                            // miss, which has no marker to wait for.
                            val trackerD = (dValue as? ScoreShotValue)?.let { v ->
                                when {
                                    measurement != null ->
                                        v.copy(score = String.format(Locale.US, "%.1f", measurement!!.score))
                                    miss != null -> v.copy(score = "0.0")
                                    else -> null
                                }
                            }

                            // Trackers run inside the rectify block so the
                            // capture decision below can see this frame's
                            // outcome while the Mats are still alive.
                            val reading = Reading(
                                System.currentTimeMillis(),
                                mapOf("D" to trackerD),
                            )
                            changes = tracker.process(reading)
                            shotOutcome = shotTracker.process(reading, measurement, miss)

                            // Capture inside the rectify block so the rectified
                            // Mat and dMat are still alive for the writer.
                            // Auto-capture fires once per *confirmed* new shot —
                            // exactly the frames worth labeling, at a volume
                            // that matches the session instead of the clock.
                            val autoCaptureDue = shotOutcome?.newShot != null && autoCaptureEnabled()
                            if (pendingCapture.compareAndSet(true, false) || autoCaptureDue) {
                                onCapture(
                                    CaptureInputs(
                                        fullFrameBgr = bgr,
                                        rectifiedMat = view.rectifiedMat,
                                        screenQuad = detected.screenQuad,
                                        dCellMat = dMat,
                                        dValue = dValue,
                                        detectionMethod = detected.detectionMethod,
                                        detectionConfidence = detected.confidence,
                                    ),
                                )
                            }
                        } finally {
                            dMat?.release()
                            targetMat?.release()
                        }
                    }
                } else if (pendingCapture.compareAndSet(true, false)) {
                    onCapture(CaptureInputs(fullFrameBgr = bgr))
                }
            } finally {
                src.close()
            }

            val text = listOfNotNull(
                dValue?.let { "D: ${it.displayString()}" },
            ).joinToString("\n").ifEmpty { detectionLabel }
            val fps = if (dt > 0) 1.0 / dt else 0.0
            val cacheLine = "D ${cellDExtractor.hitCount}h/${cellDExtractor.missCount}m"
            val debug = if (debugEnabled()) {
                DebugFrame(
                    detectionLabel = detectionLabel,
                    fps = fps,
                    cacheLine = cacheLine,
                    dCell = debugDCell,
                    dText = dValue?.displayString(),
                    dShotProcessed = debugDShotProc,
                    dConfidence = debugOcrConfidence?.invoke(),
                )
            } else null

            onFrameProcessed(
                FrameResult(
                    readingText = text,
                    fps = fps,
                    cacheLine = cacheLine,
                    detectionLabel = detectionLabel,
                    confidence = confidence,
                    overlay = nextOverlay,
                    changes = changes,
                    shotOutcome = shotOutcome,
                    debug = debug,
                ),
            )
        } finally {
            image.close()
        }
    }

    /** BGR (or single-channel binary) cell Mat → Bitmap for the debug panel. */
    private fun cellToBitmap(mat: Mat): Bitmap? {
        if (mat.empty()) return null
        val rgba = Mat()
        return try {
            val code = if (mat.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_BGR2RGBA
            Imgproc.cvtColor(mat, rgba, code)
            val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            bmp
        } finally {
            rgba.release()
        }
    }
}
