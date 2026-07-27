package se.linusborjesson.scorespeaker.pipeline

import se.linusborjesson.scorespeaker.settings.OffsetStyle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The one place a green-dot offset becomes spoken words — used by both the
 * per-shot announcements and the key-press queries so every path sounds the
 * same and obeys the same settings.
 *
 * Styles:
 *  - [OffsetStyle.DIRECTIONS] — per-axis words: "left 3 point 1, down 1".
 *    [withDecimal] applies: "left 3 point 1" vs "left 3".
 *  - [OffsetStyle.CLOCK] — the hit's clock direction only: "3 o'clock"
 *    (3 = right, 6 = low, 9 = left, 12 = high). The score is already
 *    announced separately, so the clock call carries no number and
 *    [withDecimal] doesn't apply.
 */
object OffsetSpeech {

    /** Below this offset (rings) an axis — or the whole hit — is centred. */
    const val CENTRED_RINGS = 0.05

    /**
     * Format an offset, or null when it's centred (callers suppress or say
     * "centred" as fits their context).
     */
    fun phrase(
        offsetXRings: Double,
        offsetYRings: Double,
        speech: SpeechStrings,
        style: OffsetStyle = OffsetStyle.DIRECTIONS,
        withDecimal: Boolean = true,
    ): String? = when (style) {
        OffsetStyle.DIRECTIONS -> directions(offsetXRings, offsetYRings, speech, withDecimal)
        OffsetStyle.CLOCK -> clock(offsetXRings, offsetYRings, speech)
    }

    private fun directions(x: Double, y: Double, speech: SpeechStrings, withDecimal: Boolean): String? {
        fun axis(value: Double, positive: String, negative: String): String? {
            val magnitude = abs(value)
            return if (withDecimal) {
                if (magnitude < CENTRED_RINGS) null
                else "${if (value > 0) positive else negative} ${pronounce("%.1f".format(magnitude), speech)}"
            } else {
                val whole = magnitude.roundToInt()
                if (whole == 0) null
                else "${if (value > 0) positive else negative} $whole"
            }
        }
        val parts = listOfNotNull(
            axis(x, speech.right, speech.left),
            axis(y, speech.down, speech.up),
        )
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    private fun clock(x: Double, y: Double, speech: SpeechStrings): String? {
        if (hypot(x, y) < CENTRED_RINGS) return null
        // Image coords: x grows right, y grows down. Clock: 12 up, 3 right.
        val degrees = Math.toDegrees(atan2(x, -y))
        val hour = ((degrees / 30.0).roundToInt().mod(12)).let { if (it == 0) 12 else it }
        return speech.clockTemplate.format(hour)
    }

    /** "7.6" → "7 point 6" (also handles comma-decimal locales). */
    fun pronounce(s: String, speech: SpeechStrings): String =
        s.replace(".", " ${speech.decimalWord} ").replace(",", " ${speech.decimalWord} ")
}
