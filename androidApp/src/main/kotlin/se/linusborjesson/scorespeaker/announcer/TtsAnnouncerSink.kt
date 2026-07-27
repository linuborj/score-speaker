package se.linusborjesson.scorespeaker.announcer

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import se.linusborjesson.scorespeaker.pipeline.Announcement
import se.linusborjesson.scorespeaker.pipeline.AnnouncerSink
import se.linusborjesson.scorespeaker.pipeline.EnglishSpeech
import se.linusborjesson.scorespeaker.pipeline.Priority
import se.linusborjesson.scorespeaker.pipeline.SpeechStrings
import java.util.Locale
import java.util.UUID

/**
 * Android implementation of [AnnouncerSink] backed by [TextToSpeech].
 *
 * Wires straight into the JVM-tested [se.linusborjesson.scorespeaker.pipeline.Announcer]
 * — every [Announcement] becomes a TTS utterance. HIGH-priority utterances
 * flush the queue (the user wants the latest score, not a backlog).
 *
 * Lifecycle: construct after the [TextToSpeech] init callback fires, and
 * call [shutdown] from `onDestroy`. The class is a single-instance owner of
 * its TTS — don't share across activities.
 *
 * Locale defaults to the device default. SIUS displays show Swedish status
 * words ("KLAR", "STOPP") but the score itself is purely numeric, so the
 * device's TTS handles both reasonably when set to Swedish OR English.
 * Pick `Locale("sv", "SE")` if you want "ten point four" → "tio komma fyra".
 */
class TtsAnnouncerSink(
    context: Context,
    /**
     * The language to *pin* the engine to — pass `speechLocaleFor()` so the
     * voice matches the language of the strings being spoken, rather than
     * whatever the system happens to be set to.
     */
    private val locale: Locale = Locale.getDefault(),
    /** Wording for failure messages; match the app's speech language. */
    private val speech: SpeechStrings = EnglishSpeech,
    private val pitch: Float = 1.0f,
    private val rate: Float = 1.1f,
    private val onReady: () -> Unit = {},
    /**
     * Invoked when the engine fails to init or no usable language is
     * available — the app would otherwise be *silently* mute, which for a
     * non-visual user is indistinguishable from "no shots yet". Surface it.
     */
    private val onUnavailable: (reason: String) -> Unit = {},
) : AnnouncerSink {

    private var ready: Boolean = false

    // The language the app currently wants; init may complete after a
    // runtime setLanguage() call, so it always applies the latest.
    private var desiredLocale: Locale = locale

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) {
            se.linusborjesson.scorespeaker.Log.warn { "TTS init failed (status=$status)" }
            onUnavailable(speech.ttsEngineFailed)
            return@TextToSpeech
        }
        if (!pinLanguage(desiredLocale)) {
            onUnavailable(speech.ttsNoVoiceTemplate.format(desiredLocale.displayLanguage))
            return@TextToSpeech
        }
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        ready = true
        onReady()
    }

    /**
     * Pin the engine to [target]'s voice. setLanguage can fail per-locale
     * (missing voice data); announcements would then use whatever the
     * engine happened to have, or nothing. Fall back to English — scores
     * are numeric and read fine — and return false only when even that
     * fails.
     */
    private fun pinLanguage(target: Locale): Boolean {
        val result = tts.setLanguage(target)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            se.linusborjesson.scorespeaker.Log.warn { "TTS language $target unavailable ($result), falling back to English" }
            val fallback = tts.setLanguage(Locale.ENGLISH)
            return !(fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED)
        }
        return true
    }

    /** Re-pin the voice at runtime (the language setting changed). */
    fun setLanguage(newLocale: Locale) {
        desiredLocale = newLocale
        if (ready) pinLanguage(newLocale)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
        })
    }

    override fun announce(announcement: Announcement) {
        if (!ready) return  // drop pre-init announcements; we don't queue them
        val queueMode = when (announcement.priority) {
            Priority.HIGH -> TextToSpeech.QUEUE_FLUSH      // interrupt; score is what matters
            Priority.MEDIUM, Priority.LOW -> TextToSpeech.QUEUE_ADD
        }
        tts.speak(
            announcement.text,
            queueMode,
            null,
            "scorespeaker-${UUID.randomUUID()}",
        )
    }

    /** Update the speech rate at runtime (from Settings). */
    fun setSpeechRate(rate: Float) {
        if (ready) tts.setSpeechRate(rate)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        ready = false
    }
}
