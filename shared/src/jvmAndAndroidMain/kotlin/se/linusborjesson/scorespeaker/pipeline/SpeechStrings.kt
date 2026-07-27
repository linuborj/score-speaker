package se.linusborjesson.scorespeaker.pipeline

import java.util.Locale

/**
 * Every string the app *speaks*, in one language. The TTS engine's voice
 * follows the system locale, so the words handed to it must match — a
 * Swedish voice reading English phrases mangles both languages.
 *
 * Selection is by system language via [speechStringsFor]; English is the
 * fallback. Pipeline classes default to [EnglishSpeech] so tests stay
 * deterministic regardless of the host locale.
 */
data class SpeechStrings(
    /** "Shot 7" prefix word. */
    val shot: String,
    /** Spoken for mode "P" (SIUS practice/sighting shot). */
    val practice: String,
    /** Read out for the decimal separator: "7 point 6" / "7 komma 6". */
    val decimalWord: String,
    val left: String,
    val right: String,
    val up: String,
    val down: String,
    /** Offset small enough to not merit a direction. */
    val centred: String,
    /** Spoken for a shot the display placed off the target face. */
    val miss: String,
    val noShotsYet: String,
    val noScoreRead: String,
    /** Clock-style error call; %s = clock hour. */
    val clockTemplate: String,
    /** Template for the average-error query; %d = number of shots used. */
    val averageErrorTemplate: String,
    val noMeasurementsYet: String,
    /** TTS failure surfaces (toast; TalkBack reads it). */
    val ttsEngineFailed: String,
    /** %s = the locale that lacks voice data. */
    val ttsNoVoiceTemplate: String,
    val announcementsOff: String,
    /** Rear camera missing or failed to open (toast; TalkBack reads it). */
    val cameraUnavailable: String,
)

val EnglishSpeech = SpeechStrings(
    shot = "Shot",
    practice = "practice",
    decimalWord = "point",
    left = "left",
    right = "right",
    up = "up",
    down = "down",
    centred = "centred",
    miss = "miss",
    clockTemplate = "%s o'clock",
    noShotsYet = "No shots yet",
    noScoreRead = "no score read",
    averageErrorTemplate = "Average error last %d shots",
    noMeasurementsYet = "No measurements yet",
    ttsEngineFailed = "Text-to-speech engine failed to start",
    ttsNoVoiceTemplate = "No text-to-speech voice installed for %s or English",
    announcementsOff = "announcements are off",
    cameraUnavailable = "This device has no usable rear camera",
)

val SwedishSpeech = SpeechStrings(
    shot = "Skott",
    practice = "provskott",
    decimalWord = "komma",
    left = "vänster",
    right = "höger",
    up = "upp",
    down = "ner",
    centred = "centrerat",
    miss = "bom",
    clockTemplate = "klockan %s",
    noShotsYet = "Inga skott än",
    noScoreRead = "ingen poäng läst",
    averageErrorTemplate = "Snittfel senaste %d skotten",
    noMeasurementsYet = "Inga mätningar än",
    ttsEngineFailed = "Talsyntesen kunde inte startas",
    ttsNoVoiceTemplate = "Ingen talsyntesröst installerad för %s eller engelska",
    announcementsOff = "uppläsningen är avstängd",
    cameraUnavailable = "Enheten saknar en användbar bakre kamera",
)

/** The speech language for [locale]: Swedish for `sv`, otherwise English. */
fun speechStringsFor(locale: Locale = Locale.getDefault()): SpeechStrings =
    if (locale.language == "sv") SwedishSpeech else EnglishSpeech

/**
 * The locale to *pin* the TTS engine to — always the language of the
 * strings we speak, never blindly the system's. A system set to, say,
 * German would otherwise read our English phrases with a German voice.
 * Swedish system → Swedish voice; everything else → English voice.
 */
fun speechLocaleFor(locale: Locale = Locale.getDefault()): Locale =
    if (locale.language == "sv") Locale("sv", "SE") else Locale.ENGLISH
