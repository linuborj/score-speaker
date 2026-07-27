package se.linusborjesson.scorespeaker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import se.linusborjesson.scorespeaker.Log
import se.linusborjesson.scorespeaker.announcer.TtsAnnouncerSink
import se.linusborjesson.scorespeaker.ocr.CellDExtractor
import se.linusborjesson.scorespeaker.pipeline.Announcement
import se.linusborjesson.scorespeaker.pipeline.AnnouncementSource
import se.linusborjesson.scorespeaker.pipeline.Priority
import se.linusborjesson.scorespeaker.pipeline.ShotQueries
import se.linusborjesson.scorespeaker.pipeline.speechLocaleFor
import se.linusborjesson.scorespeaker.pipeline.speechStringsFor
import se.linusborjesson.scorespeaker.settings.BindableAction
import se.linusborjesson.scorespeaker.ui.KeyBindingRowUi
import se.linusborjesson.scorespeaker.ui.uiStrings
import se.linusborjesson.scorespeaker.ocr.DigitTemplateMatcher
import se.linusborjesson.scorespeaker.processing.MissBadgeDetector
import se.linusborjesson.scorespeaker.processing.loadShotFontFromAssets
import se.linusborjesson.scorespeaker.pipeline.Announcer
import se.linusborjesson.scorespeaker.pipeline.CachedCellExtractor
import se.linusborjesson.scorespeaker.pipeline.ChangeTracker
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.pipeline.AnnouncerPolicy
import se.linusborjesson.scorespeaker.db.DriverFactory
import se.linusborjesson.scorespeaker.db.createDatabase
import se.linusborjesson.scorespeaker.pipeline.LockingScreenDetector
import se.linusborjesson.scorespeaker.pipeline.ShotHistory
import se.linusborjesson.scorespeaker.pipeline.ShotRecorder
import se.linusborjesson.scorespeaker.pipeline.ShotTracker
import se.linusborjesson.scorespeaker.settings.AppSettings
import se.linusborjesson.scorespeaker.settings.Verbosity
import se.linusborjesson.scorespeaker.processing.GreenDotDetector
import se.linusborjesson.scorespeaker.processing.MultiScaleMatcher
import se.linusborjesson.scorespeaker.processing.TargetScoring
import se.linusborjesson.scorespeaker.processing.loadTemplateFromAssets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MVP camera loop:
 *  - rear camera preview via CameraX.
 *  - per frame: [MultiScaleMatcher] detects the SIUS display in the source
 *    frame, [RectifiedView] produces the canonical-resolution
 *    cells, [CellDExtractor] reads the shot number, the green-dot path
 *    measures the score. Cache via dHash, diff via [ChangeTracker], speak
 *    via [Announcer] → [TtsAnnouncerSink].
 *
 * On detection failure the tracker is NOT fed — a momentary miss between
 * good frames doesn't emit a spurious "score went null" change.
 *
 * Bundled assets:
 *   assets/templates/sius-display.png  SIUS template for MultiScaleMatcher
 *   assets/glyphs/shot-font/           real-glyph alphabet for the shot reader
 *
 * Next iterations:
 *  - Tap-to-mark calibration as a fallback for hard-to-detect angles.
 *  - Multi-screen-mode CellLayout for the pistol summary screen.
 */
class MainActivity : ComponentActivity() {

    private lateinit var ttsSink: TtsAnnouncerSink
    // Keep the inner matcher reference for asset-loading; per-frame work goes
    // through the locking wrapper so most frames skip the expensive ORB pass.
    private val innerMatcher = MultiScaleMatcher(
        scales = listOf(0.3, 0.5, 0.7, 1.0),
        maxFeatures = 500,
        minInliers = 8,
        maxInputWidth = 2500,
    )
    private val detector = LockingScreenDetector(innerMatcher)
    private val tracker = ChangeTracker()
    private val shotTracker = ShotTracker()
    // One DB shared by the recorder (write) and history (read).
    private val db by lazy { createDatabase(DriverFactory(applicationContext)) }
    private val shotRecorder: ShotRecorder by lazy { ShotRecorder(db) }
    private val shotHistory: ShotHistory by lazy { ShotHistory(db) }
    private val greenDotDetector = GreenDotDetector()
    // Calibrated against the normal-angle corpus; see docs/PIPELINE.md
    // (green-dot scoring). Will move into per-discipline config later.
    private val targetScoring = TargetScoring(
        ringSpacingRatio = 0.0376,
    )
    // Speech/UI language: the setting resolves against the system locale
    // (SYSTEM follows it; ENGLISH/SWEDISH force). Pinning the TTS voice to
    // the wording matters — a system set to some third language would
    // otherwise read our phrases with a mismatched voice.
    private val resolvedLanguage: String
        get() = settingsState.value.language.resolve(java.util.Locale.getDefault().language)
    private val speech get() = speechStringsFor(java.util.Locale(resolvedLanguage))
    private val strings get() = uiStrings(resolvedLanguage)
    // Mutable policy instance — Settings changes mutate it in place so the
    // Announcer keeps its per-shot dedup state. The sink tees to TTS *and*
    // the on-screen caption bar.
    private val policy = AnnouncerPolicy()
    private val announcer by lazy {
        Announcer(sink = { announcement -> ttsSink.announce(announcement) }, policy = policy)
    }
    private lateinit var settingsStore: SettingsStore
    private val settingsState = mutableStateOf(AppSettings())
    // Inner extractor kept separate from its cache wrapper: the debug panel
    // asks it for the reduced crop directly (cache hits skip it). The
    // real-glyph alphabet loads from bundled assets; a load failure means
    // the shot reader reads nothing — the
    // warning below is the only trace.
    private val cellDInner by lazy {
        CellDExtractor().apply {
            shotTemplateMatcher = DigitTemplateMatcher(minScore = 0.5)
                .takeIf { it.loadShotFontFromAssets(this@MainActivity) }
            if (shotTemplateMatcher == null) {
                Log.warn { "MainActivity: shot-font alphabet failed to load — shot numbers unreadable" }
            }
        }
    }
    private val cellDExtractor: CachedCellExtractor by lazy {
        CachedCellExtractor(cellDInner, tolerance = 5)
    }

    /**
     * Off-target badge reader. Shares the shot-font alphabet with the Cell D
     * reader — the badge carries the same digits, and requiring them to match
     * the confirmed shot number is what makes a miss a positive detection
     * rather than an inference from the marker's absence. Without the
     * alphabet it stays null and misses simply aren't reported.
     */
    private val missBadgeDetector: MissBadgeDetector? by lazy {
        cellDInner.shotTemplateMatcher?.let { matcher ->
            MissBadgeDetector().apply { shotTemplateMatcher = matcher }
        }
    }
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Single snapshot the live screen renders. Updated field-by-field (copy)
    // from the frame callback, the announcer sink, capture, and permission.
    private val liveState = mutableStateOf(LiveUiState())

    // Consumed by the next analysed frame (on the analyzer thread, where the
    // Mats are still alive). Set by the CAPTURE button on the debug panel.
    private val pendingCapture = AtomicBoolean(false)

    // The bound camera, for runtime controls (zoom). Main-thread only.
    private var boundCamera: androidx.camera.core.Camera? = null

    // Keeps ImageAnalysis.targetRotation in sync with the physical display
    // rotation — see the comment at the bind site. An OrientationEventListener
    // (not onConfigurationChanged) because a landscape↔reverse-landscape flip
    // changes rotation without a configuration change.
    private var analysisUseCase: ImageAnalysis? = null
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_270
                    orientation < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                analysisUseCase?.let { if (it.targetRotation != rotation) it.targetRotation = rotation }
            }
        }
    }

    // Action id currently waiting for a key press in Settings, or null.
    private val listeningBindAction = mutableStateOf<String?>(null)

    // BACK cancels an in-progress key-binding capture. Registered on the
    // OnBackPressedDispatcher (not intercepted in onKeyDown) so predictive
    // back keeps working. Added when listening starts — the dispatcher
    // invokes the most recently added enabled callback, and this must
    // outrank the Compose navigation BackHandlers registered during
    // composition; removed when listening ends so normal back navigation
    // is untouched otherwise.
    private val cancelBindingOnBack = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            setListeningBindAction(null)
        }
    }

    private fun setListeningBindAction(actionId: String?) {
        listeningBindAction.value = actionId
        cancelBindingOnBack.remove()
        if (actionId != null) onBackPressedDispatcher.addCallback(this, cancelBindingOnBack)
    }

    /**
     * Hardware keys (keyboard / BT numpad). Two modes:
     *  - a Settings row is listening → the pressed key becomes that action's
     *    binding (stealing it from any other action; BACK cancels);
     *  - otherwise a bound key runs its query and speaks the answer.
     * Queries bypass the Announcer's toggles/dedup on purpose — an explicit
     * key press always deserves an answer.
     */
    // GestureBackNavigation: back is NOT intercepted here — the KEYCODE_BACK
    // reference only declines to *bind* it as a query key (binding would
    // hijack back on that keyboard) and delegates to super, where the
    // OnBackPressedDispatcher (cancelBindingOnBack) handles it.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        listeningBindAction.value?.let { actionId ->
            if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
            setListeningBindAction(null)
            val stolen = settingsState.value.keyBindings.filterValues { it != keyCode }
            updateSettings(settingsState.value.copy(keyBindings = stolen + (actionId to keyCode)))
            return true
        }
        val actionId = settingsState.value.keyBindings.entries
            .firstOrNull { it.value == keyCode }?.key
            ?: return super.onKeyDown(keyCode, event)
        runBoundAction(actionId)
        return true
    }

    private fun runBoundAction(actionId: String) {
        val s = settingsState.value
        val text = when (BindableAction.byId(actionId)) {
            BindableAction.READ_LAST_SCORE ->
                ShotQueries.lastShotText(shotTracker.log, speech, s.offsetStyle, s.offsetDecimals)
            BindableAction.READ_AVG_ERROR_5 ->
                ShotQueries.averageErrorText(shotTracker.log, speech = speech)
            null -> return
        }
        ttsSink.announce(
            Announcement(
                text = text,
                priority = Priority.HIGH,
                timestamp = System.currentTimeMillis(),
                source = AnnouncementSource.FromQuery,
            ),
        )
    }

    /** "KEYCODE_NUMPAD_1" → "NUMPAD 1" for the Settings row. */
    private fun keyLabel(code: Int): String =
        KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Flip the Compose state — the CameraScreen recomposes, the AndroidView
        // factory runs, and the LaunchedEffect below binds the camera once the
        // PreviewView exists. If denied, we just show the "need permission" UI.
        liveState.value = liveState.value.copy(hasPermission = granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sessions run hands-off (phone on a tripod, shooter at the line) —
        // if the screen sleeps, the activity pauses and the lifecycle-bound
        // camera pipeline stops with it. Keep the screen on while the app is
        // foregrounded; the flag releases automatically when it isn't, so it
        // can't leak battery afterwards.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Load settings before the TTS sink so the persisted language choice
        // decides the initial voice.
        settingsStore = SettingsStore(this)
        settingsState.value = settingsStore.load()
        // Apply the persisted speech rate once TTS finishes initialising.
        ttsSink = TtsAnnouncerSink(
            this,
            locale = speechLocaleFor(java.util.Locale(resolvedLanguage)),
            speech = speech,
            onReady = { ttsSink.setSpeechRate(settingsState.value.speechRate) },
            onUnavailable = { reason ->
                // The whole app is spoken output — a mute app must say so
                // somewhere. TalkBack reads toasts aloud.
                mainHandler.post {
                    Toast.makeText(this, "$reason — ${speech.announcementsOff}", Toast.LENGTH_LONG).show()
                }
            },
        )
        CoordinateTransform.ensureOpenCv()
        applySettings(settingsState.value)

        liveState.value = liveState.value.copy(hasPermission = hasCameraPermission())
        if (!innerMatcher.loadTemplateFromAssets(this)) {
            // Without a template we can still run the camera + UI, but every
            // frame's detect() will miss. The screen will just show SEARCHING.
            Log.warn { "MainActivity: SIUS template failed to load from assets/templates/" }
        }
        setContent {
            val bindingRows = BindableAction.entries.map { action ->
                KeyBindingRowUi(
                    actionId = action.id,
                    label = strings.actionLabel(action),
                    keyLabel = settingsState.value.keyBindings[action.id]?.let(::keyLabel),
                    listening = listeningBindAction.value == action.id,
                )
            }
            AppRoot(
                live = liveState.value,
                settings = settingsState.value,
                onSettingsChange = ::updateSettings,
                keyBindings = bindingRows,
                onKeyBindingClick = { id ->
                    setListeningBindAction(if (listeningBindAction.value == id) null else id)
                },
                onKeyBindingClear = { id ->
                    setListeningBindAction(null)
                    updateSettings(settingsState.value.copy(keyBindings = settingsState.value.keyBindings - id))
                },
                onPreviewReady = ::startCamera,
                // Pinch on the live screen edits the same persisted zoom the
                // Settings slider does; clamp to the slider's 1–5× range so
                // the two controls agree.
                onZoomGesture = { factor ->
                    val next = (settingsState.value.zoomRatio * factor).coerceIn(1f, 5f)
                    updateSettings(settingsState.value.copy(zoomRatio = next))
                },
                // Opens the site's policy in the browser — the app itself has
                // no network access; the intent hands off to the browser app.
                onPrivacyPolicyClick = {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(PRIVACY_POLICY_URL),
                        ),
                    )
                },
                loadLicensesText = {
                    assets.open("third-party-notices.txt").bufferedReader().use { it.readText() }
                },
                // Consumed by the next analysed frame, which captures with
                // whatever it detects (or the raw frame on a detection miss).
                onCaptureClick = { pendingCapture.set(true) },
                loadSessions = { shotHistory.sessions() },
                now = { System.currentTimeMillis() },
            )
        }

        if (!liveState.value.hasPermission) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun updateSettings(s: AppSettings) {
        settingsState.value = s
        settingsStore.save(s)
        applySettings(s)
    }

    /** Push [AppSettings] into the live announcer policy + TTS rate/voice. */
    private fun applySettings(s: AppSettings) {
        applyZoom(s.zoomRatio)
        policy.speech = speech
        if (::ttsSink.isInitialized) {
            ttsSink.setLanguage(speechLocaleFor(java.util.Locale(resolvedLanguage)))
        }
        policy.announceScore = s.announceScore
        policy.announceMeasurement = s.announceOffset
        policy.offsetStyle = s.offsetStyle
        policy.offsetDecimals = s.offsetDecimals
        when (s.verbosity) {
            Verbosity.TERSE -> {
                policy.includeShotNumber = false; policy.includeMode = false
            }
            Verbosity.NORMAL -> {
                policy.includeShotNumber = true; policy.includeMode = true
            }
        }
        if (::ttsSink.isInitialized) ttsSink.setSpeechRate(s.speechRate)
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        ttsSink.shutdown()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                bindCamera(providerFuture.get(), previewView)
            } catch (e: Exception) {
                // Play filters on the rear-camera <uses-feature>, so this is
                // the sideload path: front-only tablet, emulator, or a broken
                // camera HAL. The app is useless here but must not crash.
                // Toast for the immediate spoken/visible cue (TalkBack reads
                // it); the state flag keeps a permanent message on screen.
                Log.warn { "Camera unavailable: ${e.message}" }
                liveState.value = liveState.value.copy(cameraUnavailable = true)
                Toast.makeText(this, speech.cameraUnavailable, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider, previewView: PreviewView) {
        val preview = Preview.Builder().build().also { p ->
            p.setSurfaceProvider(previewView.surfaceProvider)
        }
        // Request 1080p analyzer frames. CameraX picks the closest
        // supported size — the default would be ~640×480, far too small
        // for OCR on a SIUS display held at arm's length.
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(resolutionSelector)
            .build()
        // configChanges keeps this activity alive across rotation, so
        // CameraX never learns the display turned — targetRotation would
        // stay whatever it was at bind time and imageInfo.rotationDegrees
        // would be stale (frames upright in the bind orientation, 90° off
        // in the other; the rotation-invariant pipeline still detects,
        // but overlay coordinates land in the wrong frame space). Seed it
        // now and keep it current via the orientation listener.
        analysis.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        analysisUseCase = analysis
        analysis.setAnalyzer(
            analysisExecutor,
            FrameAnalyzer(
                detector = detector,
                cellDExtractor = cellDExtractor,
                greenDotDetector = greenDotDetector,
                targetScoring = targetScoring,
                tracker = tracker,
                shotTracker = shotTracker,
                missBadgeDetector = missBadgeDetector,
                announceMissesEnabled = { settingsState.value.announceMisses },
                pendingCapture = pendingCapture,
                onCapture = ::doCapture,
                debugEnabled = { BuildConfig.DEBUG && settingsState.value.debugOverlay },
                debugProcessedCells = { m -> cellDInner.debugProcessedShot(m) },
                debugOcrConfidence = {
                    fun pct(c: Float?) = c?.let { "${(it * 100).toInt()}%" } ?: "—"
                    cellDInner.lastDetail?.let { d ->
                        "glyph ${pct(d.shotConfidence)}"
                    }
                },
                autoCaptureEnabled = ::autoCaptureAllowed,
                onFrameProcessed = { result ->
                    // Still on the analyzer thread here. The DB write
                    // happens now — SQLite on the main thread is a jank
                    // risk — so ShotRecorder is analyzer-thread-confined;
                    // ShotHistory reads the same database from main, which
                    // the Android driver serializes.
                    result.shotOutcome?.let { shotRecorder.record(it) }
                    mainHandler.post {
                        result.changes?.let { announcer.onChanges(it) }
                        // The announcer stays on main deliberately: its
                        // per-shot dedup state is also touched by the
                        // main-thread key-query path.
                        result.shotOutcome?.let { announcer.onShotOutcome(it) }
                        // Build the readout snapshot from the shot log.
                        val current = shotTracker.current
                        val measurement = current?.measurement
                        liveState.value = liveState.value.copy(
                            locked = result.overlay != null,
                            overlay = result.overlay,
                            shotNumber = current?.shotNumber,
                            score = current?.score,
                            offsetXRings = measurement?.offsetXRings,
                            offsetYRings = measurement?.offsetYRings,
                            debug = result.debug,
                        )
                    }
                },
            ),
        )

        provider.unbindAll()
        boundCamera = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
        applyZoom(settingsState.value.zoomRatio)
    }

    /**
     * Apply the persisted zoom, clamped to what the device supports. On
     * multi-camera phones the logical camera transparently switches to the
     * telephoto lens at higher ratios — more real pixels on the display.
     */
    private fun applyZoom(ratio: Float) {
        val camera = boundCamera ?: return
        val range = camera.cameraInfo.zoomState.value ?: return
        camera.cameraControl.setZoomRatio(ratio.coerceIn(range.minZoomRatio, range.maxZoomRatio))
    }

    /**
     * Gate for the per-shot auto-capture (called from the analyzer thread
     * when a shot is confirmed). Hard to screw up by design: when the
     * capture volume drops under [MIN_CAPTURE_FREE_BYTES] the persisted
     * setting itself is turned OFF — visible in Settings, won't silently
     * re-arm — and a toast says why.
     */
    private fun autoCaptureAllowed(): Boolean {
        if (!BuildConfig.DEBUG || !settingsState.value.debugAutoCapture) return false
        val free = (getExternalFilesDir(null) ?: filesDir).usableSpace
        if (free >= MIN_CAPTURE_FREE_BYTES) return true

        mainHandler.post {
            // Re-check on the main thread: a few frames may race this post.
            if (settingsState.value.debugAutoCapture) {
                updateSettings(settingsState.value.copy(debugAutoCapture = false))
                Log.warn { "Auto-capture disabled: ${free / (1 shl 20)} MB free < 1 GB minimum" }
                Toast.makeText(
                    this,
                    "Auto-capture turned off — less than 1 GB free on storage",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        return false
    }

    private fun doCapture(inputs: CaptureInputs) {
        // Defense in depth for the privacy policy's "frames are never
        // saved" claim: both triggers (debug-panel button, auto-capture)
        // are already DEBUG-gated, but the write itself must never depend
        // on callers remembering that. Release builds cannot save frames.
        if (!BuildConfig.DEBUG) return
        try {
            val dir = writeCapture(this, inputs)
            Log.info { "Capture saved: ${dir.name} (detect=${inputs.screenQuad != null})" }
        } catch (e: Exception) {
            Log.warn { "Capture failed: ${e.message}" }
        }
    }

    private companion object {
        const val PRIVACY_POLICY_URL = "https://scorespeaker.linusborjesson.se/#privacy"
        /** Auto-capture stops (and turns itself off) below this much free space. */
        const val MIN_CAPTURE_FREE_BYTES = 1L shl 30 // 1 GB
    }
}

