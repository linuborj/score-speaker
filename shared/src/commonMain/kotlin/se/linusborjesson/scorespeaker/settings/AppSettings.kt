package se.linusborjesson.scorespeaker.settings

/** How much the announcer says per shot. */
enum class Verbosity { TERSE, NORMAL }

/** How the aim offset is spoken. */
enum class OffsetStyle {
    /** "left 3 point 1, down 1" — per-axis direction words. */
    DIRECTIONS,
    /** "3 o'clock" — the hit's clock direction (score is announced separately). */
    CLOCK,
}

/** App language: follow the system, or force one of the supported languages. */
enum class AppLanguage {
    SYSTEM, ENGLISH, SWEDISH;

    /** The effective BCP-47 language code, given the system's. */
    fun resolve(systemLanguage: String): String = when (this) {
        SYSTEM -> systemLanguage
        ENGLISH -> "en"
        SWEDISH -> "sv"
    }
}

/**
 * User-tunable announcement behaviour. Only knobs with real backing live
 * here — e.g. a voice picker or millimetre units would need plumbing that
 * doesn't exist yet, so they aren't offered.
 *
 * - [announceScore] — speak the score on each new shot.
 * - [announceOffset] — speak the green-dot offset ("left 3, down 1").
 * - [verbosity] — the score utterance's prefix. TERSE: just the score;
 *   NORMAL: shot number + practice flag first. (The offset utterance is
 *   governed by [announceOffset], not verbosity.)
 * - [speechRate] — TTS rate multiplier (0.5–2.0).
 * - [debugOverlay] — show the pipeline debug panel on the live screen:
 *   detection HUD (method, confidence, fps, read cache) and the actual
 *   Cell D crop the reader ran on, with what it read from it.
 * - [debugAutoCapture] — while the display is locked, save a fully annotated
 *   capture (source frame, rectified view, cell crops, detection + read
 *   values) once per confirmed shot. For building the corpus from live
 *   sessions; pull with adb into the corpus dir. Turns itself off when the
 *   capture volume has less than 1 GB free.
 */
data class AppSettings(
    val announceScore: Boolean = true,
    val announceOffset: Boolean = true,
    /**
     * Speak shots the display placed off the target face ("shot 16, miss,
     * 8 o'clock"). Without this such a shot is silent — there is no marker
     * on the face, so nothing is measured and nothing is said. Detection
     * is gated on reading the shot number off the display's own edge
     * badge, so it can't fire on a merely-unseen marker.
     */
    val announceMisses: Boolean = true,
    val verbosity: Verbosity = Verbosity.NORMAL,
    /** UI + speech language; SYSTEM follows the device locale. */
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** Speak offsets/scores with a decimal ("left 3.1") or whole ("left 3"). */
    val offsetDecimals: Boolean = true,
    /** Spoken error format — direction words or clock position. */
    val offsetStyle: OffsetStyle = OffsetStyle.CLOCK,
    val speechRate: Float = 1.0f,
    /**
     * Camera zoom ratio, set once by the sighted helper to fill the frame
     * with the display. Clamped to the device's supported range at apply
     * time; ratios ≥ ~2× transparently engage the telephoto lens where
     * one exists (more real pixels on the display).
     */
    val zoomRatio: Float = 1.0f,
    val debugOverlay: Boolean = false,
    val debugAutoCapture: Boolean = false,
    /**
     * Hardware-key bindings: [BindableAction.id] → platform key code
     * (Android `KeyEvent` keyCode). Lets a connected keyboard / BT numpad
     * trigger spoken queries without touching the screen — the end user is
     * blind; a physical key in the pocket beats any on-screen control.
     */
    val keyBindings: Map<String, Int> = emptyMap(),
)

/**
 * Actions a hardware key can be bound to. [id] is the stable persistence
 * key (never rename); the UI label comes from `Strings.actionLabel`.
 */
enum class BindableAction(val id: String) {
    READ_LAST_SCORE("read_last_score"),
    READ_AVG_ERROR_5("read_avg_error_5");

    companion object {
        fun byId(id: String): BindableAction? = entries.firstOrNull { it.id == id }
    }
}
