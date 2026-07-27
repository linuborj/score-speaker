package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Test
import se.linusborjesson.scorespeaker.pipeline.EnglishSpeech
import se.linusborjesson.scorespeaker.pipeline.OffsetSpeech
import se.linusborjesson.scorespeaker.pipeline.SwedishSpeech
import se.linusborjesson.scorespeaker.settings.OffsetStyle
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OffsetSpeechTest {

    private fun phrase(
        x: Double,
        y: Double,
        style: OffsetStyle = OffsetStyle.DIRECTIONS,
        withDecimal: Boolean = true,
    ) = OffsetSpeech.phrase(x, y, EnglishSpeech, style, withDecimal)

    @Test
    fun `directions with decimals — the existing voice`() {
        assertEquals("left 3 point 1, down 1 point 0", phrase(-3.1, 1.0))
    }

    @Test
    fun `directions without decimals round each axis`() {
        assertEquals("left 3, down 1", phrase(-3.1, 1.0, withDecimal = false))
        // 0.4 rounds to 0 → the axis drops out entirely.
        assertEquals("down 1", phrase(-0.4, 1.2, withDecimal = false))
    }

    @Test
    fun `directions without decimals where everything rounds to zero is centred`() {
        assertNull(phrase(-0.4, 0.3, withDecimal = false))
    }

    @Test
    fun `clock style speaks only the hit direction — score is announced separately`() {
        // Pure right = 3 o'clock; up = 12; down = 6; left = 9.
        assertEquals("3 o'clock", phrase(2.0, 0.0, style = OffsetStyle.CLOCK))
        assertEquals("12 o'clock", phrase(0.0, -2.0, style = OffsetStyle.CLOCK))
        assertEquals("6 o'clock", phrase(0.0, 2.0, style = OffsetStyle.CLOCK))
        assertEquals("9 o'clock", phrase(-2.0, 0.0, style = OffsetStyle.CLOCK))
        // Down-left diagonal: exactly 225° → 7.5 → rounds to 8.
        assertEquals("8 o'clock", phrase(-2.0, 2.0, style = OffsetStyle.CLOCK))
    }

    @Test
    fun `clock style ignores the decimals toggle — it carries no number`() {
        assertEquals(phrase(2.0, 0.0, style = OffsetStyle.CLOCK, withDecimal = true),
            phrase(2.0, 0.0, style = OffsetStyle.CLOCK, withDecimal = false))
    }

    @Test
    fun `clock style dead centre is suppressed`() {
        assertNull(phrase(0.0, 0.0, style = OffsetStyle.CLOCK))
    }

    @Test
    fun `clock style in Swedish`() {
        assertEquals(
            "klockan 3",
            OffsetSpeech.phrase(2.0, 0.0, SwedishSpeech, OffsetStyle.CLOCK, withDecimal = true),
        )
    }
}
