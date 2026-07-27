package se.linusborjesson.scorespeaker.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Score Speaker color system — a calm, clinical, high-contrast dark
 * instrument. Colors are *semantic*, never decorative: mint = on-centre /
 * active / locked, amber = directional guidance, red = far / error, blue =
 * camera / neutral data.
 *
 * These are the exact tokens from the design handoff (`styles.css` `:root`).
 * Material 3's `ColorScheme` doesn't have slots for this many semantic roles
 * (four text levels, six surfaces, four signal colors + their soft tints), so
 * the tokens live here and are provided via [LocalScoreColors]. The Material
 * scheme is a thin mapping for the few stock components we use.
 */
@Immutable
data class ScoreColors(
    /** True for the dark palette — lets components pick dark/light brand variants. */
    val isDark: Boolean = true,
    // surfaces
    val bg: Color = Color(0xFF0B0E13),
    val surface1: Color = Color(0xFF121822),
    val surface2: Color = Color(0xFF1A2230),
    val surface3: Color = Color(0xFF232E3D),
    val line: Color = Color(0xFF29333F),
    val line2: Color = Color(0xFF38465A),
    // text
    val textHi: Color = Color(0xFFEEF3F8),
    val text: Color = Color(0xFFC2CDD9),
    val textMid: Color = Color(0xFF8092A1),
    val textDim: Color = Color(0xFF5A6876),
    // signal
    val good: Color = Color(0xFF29D8A6),
    val goodInk: Color = Color(0xFF04231C),
    val goodSoft: Color = Color(0x2429D8A6),   // alpha 0.14
    val guide: Color = Color(0xFFFFB23D),
    val bad: Color = Color(0xFFFF5C6C),
    val badSoft: Color = Color(0x24FF5C6C),
    val info: Color = Color(0xFF5BA8FF),
    val infoSoft: Color = Color(0x245BA8FF),
)

/** The dark instrument palette — the [ScoreColors] defaults, named for symmetry. */
fun darkScoreColors() = ScoreColors()

/**
 * Light counterpart, same semantic roles: signal hues darkened enough to
 * carry contrast on paper-light surfaces (the dark theme's mint/amber/blue
 * are tuned for near-black and wash out on white).
 */
fun lightScoreColors() = ScoreColors(
    isDark = false,
    // surfaces
    bg = Color(0xFFF2F5F8),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFE9EDF2),
    surface3 = Color(0xFFDCE3EA),
    line = Color(0xFFD5DCE4),
    line2 = Color(0xFFB8C4D0),
    // text
    textHi = Color(0xFF141D26),
    text = Color(0xFF33414E),
    textMid = Color(0xFF60717F),
    textDim = Color(0xFF8C9BA9),
    // signal
    good = Color(0xFF0C9B77),
    goodInk = Color(0xFFFFFFFF),
    goodSoft = Color(0x240C9B77),
    guide = Color(0xFFB57A12),
    bad = Color(0xFFD9414F),
    badSoft = Color(0x24D9414F),
    info = Color(0xFF2E6FD0),
    infoSoft = Color(0x242E6FD0),
)

/** Corner radii. Chips and pills are fully round via [pill]. */
@Immutable
data class ScoreRadii(
    val md: androidx.compose.ui.unit.Dp = 16.dp,  // cards, buttons
    val card: androidx.compose.ui.unit.Dp = 16.dp,
    val pill: androidx.compose.ui.unit.Dp = 999.dp,
)

val LocalScoreColors = staticCompositionLocalOf { ScoreColors() }
val LocalScoreRadii = staticCompositionLocalOf { ScoreRadii() }
