package se.linusborjesson.scorespeaker.cells

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured cell values for SIUS display cells.
 * Each cell type has its own data structure matching what we expect to read.
 */
@Serializable
sealed class CellValue {
    /**
     * Check if this value matches an extracted value.
     */
    abstract fun matches(extracted: CellValue?): Boolean

    /**
     * Human-readable display string.
     */
    abstract fun displayString(): String
}

/**
 * Simple text value for cells that just contain text/numbers.
 */
@Serializable
@SerialName("text")
data class TextValue(
    val text: String
) : CellValue() {
    override fun matches(extracted: CellValue?): Boolean {
        if (extracted !is TextValue) return false
        return normalize(text) == normalize(extracted.text)
    }

    override fun displayString(): String = text

    companion object {
        private fun normalize(s: String): String {
            return s.trim().lowercase().replace(Regex("\\s+"), " ")
        }
    }
}

/**
 * Score value with optional decimal.
 * Example: "10.5", "105.2"
 */
@Serializable
@SerialName("score")
data class ScoreValue(
    val value: String
) : CellValue() {
    override fun matches(extracted: CellValue?): Boolean {
        if (extracted !is ScoreValue) return false
        return normalizeScore(value) == normalizeScore(extracted.value)
    }

    override fun displayString(): String = value

    companion object {
        private fun normalizeScore(s: String): String {
            return s.trim().replace(',', '.')
        }
    }
}

/**
 * Cell D: current shot + score. Examples: "1P 7.6" (shot 1, practice mode,
 * score 7.6); "20 7.6" (shot 20, competition, score 7.6); "10P 9.7".
 *
 * - [shot] is the shot number, kept as a string to preserve leading zeros.
 * - [mode] is "P" for Provskott / practice, null otherwise.
 * - [score] is the score with one decimal place (0.0–10.9 typical).
 */
@Serializable
@SerialName("scoreShot")
data class ScoreShotValue(
    val shot: String,
    val mode: String? = null,
    /**
     * Score digits as displayed. Null at runtime — the shot reader only
     * reads the shot region (the product score comes from the green-dot
     * measurement, which overwrites this field downstream). Annotations
     * carry the displayed score as ground truth.
     */
    val score: String? = null,
) : CellValue() {
    override fun matches(extracted: CellValue?): Boolean {
        if (extracted !is ScoreShotValue) return false
        // Score compares only when both sides carry one — runtime reads
        // never do, and shot+mode is what the sequence gate needs right.
        val scoreOk = score == null || extracted.score == null ||
            normalizeScore(score) == normalizeScore(extracted.score!!)
        return shot.trim() == extracted.shot.trim() &&
            (mode ?: "") == (extracted.mode ?: "") &&
            scoreOk
    }

    override fun displayString(): String =
        "$shot${mode ?: ""}${score?.let { " $it" } ?: ""}"

    companion object {
        private fun normalizeScore(s: String): String = s.trim().replace(',', '.')
    }
}

/** What kind of value a given cell contains. */
enum class CellType {
    TEXT,         // Generic text (lane, status, etc.)
    SCORE,        // Numeric score (e.g., "7.6")
    SCORE_SHOT,   // Shot number + mode + score (e.g., "1P 7.6")
}

/**
 * Per-cell value type — matches what's actually on screen in code's cell layout.
 * See CellLayout.siusDisplayCells. (Note: these letters don't line up 1:1 with
 * the DISPLAY.md table — that doc is stale; the code is authoritative.)
 */
object CellTypes {
    private val types = mapOf(
        "A" to CellType.TEXT,          // "Tavla/Bana 9" lane label
        "B" to CellType.TEXT,          // Timestamp "17.12.2025 15:28"
        "C" to CellType.TEXT,          // "Tävling" + shot list
        "D" to CellType.SCORE_SHOT,    // Current shot+score "1P 7.6"
        "E" to CellType.TEXT,          // Status codes "P- E10 E10"
        "F" to CellType.TEXT,          // Status word "KLAR" / "STOPP"
        "G" to CellType.TEXT,          // MTP (5-shot mean point of impact) — not used
        "H" to CellType.TEXT,          // Shooter / discipline
    )

    fun getType(cellName: String): CellType = types[cellName] ?: CellType.TEXT

    fun createValue(cellName: String, input: String): CellValue? {
        if (input.isBlank()) return null
        return when (getType(cellName)) {
            CellType.SCORE -> ScoreValue(input.trim())
            CellType.SCORE_SHOT -> ScoreShotValueParser.parse(input) ?: TextValue(input)
            CellType.TEXT -> TextValue(input.trim())
        }
    }
}

/** Parses "1P 7.6", "20 7.6" etc. into [ScoreShotValue]. */
internal object ScoreShotValueParser {
    private val pattern = Regex("""^\s*(\d+)\s*([Pp])?\s+(\d+[.,]\d+)\s*$""")
    fun parse(input: String): ScoreShotValue? {
        val m = pattern.matchEntire(input.trim()) ?: return null
        return ScoreShotValue(
            shot = m.groupValues[1],
            mode = m.groupValues[2].uppercase().ifEmpty { null },
            score = m.groupValues[3].replace(',', '.'),
        )
    }
}
