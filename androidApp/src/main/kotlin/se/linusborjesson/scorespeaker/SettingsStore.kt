package se.linusborjesson.scorespeaker

import android.content.Context
import se.linusborjesson.scorespeaker.settings.AppLanguage
import se.linusborjesson.scorespeaker.settings.AppSettings
import se.linusborjesson.scorespeaker.settings.BindableAction
import se.linusborjesson.scorespeaker.settings.OffsetStyle
import se.linusborjesson.scorespeaker.settings.Verbosity

/**
 * Persists [AppSettings] in SharedPreferences. Small and synchronous — the
 * settings set is tiny and read once at startup, written on each change.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("scorespeaker_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            announceScore = prefs.getBoolean(KEY_SCORE, d.announceScore),
            announceOffset = prefs.getBoolean(KEY_OFFSET, d.announceOffset),
            announceMisses = prefs.getBoolean(KEY_MISSES, d.announceMisses),
            verbosity = runCatching { Verbosity.valueOf(prefs.getString(KEY_VERBOSITY, d.verbosity.name)!!) }
                .getOrDefault(d.verbosity),
            language = runCatching { AppLanguage.valueOf(prefs.getString(KEY_LANGUAGE, d.language.name)!!) }
                .getOrDefault(d.language),
            offsetDecimals = prefs.getBoolean(KEY_OFFSET_DECIMALS, d.offsetDecimals),
            offsetStyle = runCatching { OffsetStyle.valueOf(prefs.getString(KEY_OFFSET_STYLE, d.offsetStyle.name)!!) }
                .getOrDefault(d.offsetStyle),
            speechRate = prefs.getFloat(KEY_RATE, d.speechRate),
            zoomRatio = prefs.getFloat(KEY_ZOOM, d.zoomRatio),
            debugOverlay = prefs.getBoolean(KEY_DEBUG_OVERLAY, d.debugOverlay),
            debugAutoCapture = prefs.getBoolean(KEY_DEBUG_AUTO_CAPTURE, d.debugAutoCapture),
            keyBindings = BindableAction.entries.mapNotNull { action ->
                prefs.getInt(KEYBIND_PREFIX + action.id, -1)
                    .takeIf { it != -1 }
                    ?.let { action.id to it }
            }.toMap(),
        )
    }

    fun save(s: AppSettings) {
        val edit = prefs.edit()
            .putBoolean(KEY_SCORE, s.announceScore)
            .putBoolean(KEY_OFFSET, s.announceOffset)
            .putBoolean(KEY_MISSES, s.announceMisses)
            .putString(KEY_VERBOSITY, s.verbosity.name)
            .putString(KEY_LANGUAGE, s.language.name)
            .putBoolean(KEY_OFFSET_DECIMALS, s.offsetDecimals)
            .putString(KEY_OFFSET_STYLE, s.offsetStyle.name)
            .putFloat(KEY_RATE, s.speechRate)
            .putFloat(KEY_ZOOM, s.zoomRatio)
            .putBoolean(KEY_DEBUG_OVERLAY, s.debugOverlay)
            .putBoolean(KEY_DEBUG_AUTO_CAPTURE, s.debugAutoCapture)
        for (action in BindableAction.entries) {
            val code = s.keyBindings[action.id]
            if (code != null) edit.putInt(KEYBIND_PREFIX + action.id, code)
            else edit.remove(KEYBIND_PREFIX + action.id)
        }
        edit.apply()
    }

    private companion object {
        const val KEY_SCORE = "announce_score"
        const val KEY_OFFSET = "announce_offset"
        const val KEY_MISSES = "announce_misses"
        const val KEY_VERBOSITY = "verbosity"
        const val KEY_LANGUAGE = "language"
        const val KEY_OFFSET_DECIMALS = "offset_decimals"
        const val KEY_OFFSET_STYLE = "offset_style"
        const val KEY_RATE = "speech_rate"
        const val KEY_ZOOM = "camera_zoom"
        const val KEY_DEBUG_OVERLAY = "debug_overlay"
        const val KEY_DEBUG_AUTO_CAPTURE = "debug_auto_capture"
        const val KEYBIND_PREFIX = "keybind_"
    }
}
