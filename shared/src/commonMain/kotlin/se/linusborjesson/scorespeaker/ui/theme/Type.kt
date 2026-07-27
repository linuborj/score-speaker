package se.linusborjesson.scorespeaker.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Typography for the instrument.
 *
 * The design calls for **IBM Plex Sans** (UI) and **IBM Plex Mono** (numeric
 * readouts, always tabular). The Plex TTFs aren't bundled yet, so these fall
 * back to the platform sans / monospace families — the weights, sizes, and
 * tabular-figure feature are all in place, so the layout is faithful and only
 * the letterforms change once Plex is dropped in.
 *
 * Drop-in path: bundle the Plex TTFs (Android `res/font`, desktop
 * `resources/font`), build the two `FontFamily`s from them, and swap
 * [plexSans] / [plexMono] below. Nothing else changes.
 */
val plexSans: FontFamily = FontFamily.SansSerif
val plexMono: FontFamily = FontFamily.Monospace

/** Mono numerics are always tabular — score columns must not jitter. */
private const val TABULAR = "tnum"

@Immutable
data class ScoreTypography(
    /** Big score readout — mint, tight. Mono 60 / 600. */
    val bigScore: TextStyle = TextStyle(
        fontFamily = plexMono, fontWeight = FontWeight.SemiBold,
        fontSize = 60.sp, lineHeight = 0.9.em, letterSpacing = (-1).sp,
        fontFeatureSettings = TABULAR,
    ),
    /** Aim magnitude. Mono 36 / 600. */
    val aimValue: TextStyle = TextStyle(
        fontFamily = plexMono, fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp, lineHeight = 1.em, fontFeatureSettings = TABULAR,
    ),
    /** Large screen title (History, Settings). Sans 26 / 600. */
    val titleLarge: TextStyle = TextStyle(
        fontFamily = plexSans, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, letterSpacing = (-0.4).sp,
    ),
    /** Screen title (Session detail). Sans 19 / 600. */
    val title: TextStyle = TextStyle(
        fontFamily = plexSans, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, letterSpacing = (-0.2).sp,
    ),
    /** Stat number — header stats, summary figures. Mono 18 / 600. */
    val stat: TextStyle = TextStyle(
        fontFamily = plexMono, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, fontFeatureSettings = TABULAR,
    ),
    /** Row titles. Sans 14.5 / 500. */
    val body: TextStyle = TextStyle(
        fontFamily = plexSans, fontWeight = FontWeight.Medium, fontSize = 14.5.sp,
    ),
    /** Sub / caption. Sans 12.5 / 400. */
    val caption: TextStyle = TextStyle(
        fontFamily = plexSans, fontWeight = FontWeight.Normal, fontSize = 12.5.sp,
    ),
    /** Eyebrow label — UPPERCASE, tracked. Sans 11 / 600. */
    val eyebrow: TextStyle = TextStyle(
        fontFamily = plexSans, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, letterSpacing = 1.6.sp,
    ),
    /** HUD telemetry strip. Mono 10.5. */
    val hud: TextStyle = TextStyle(
        fontFamily = plexMono, fontWeight = FontWeight.Normal,
        fontSize = 10.5.sp, letterSpacing = 0.5.sp, fontFeatureSettings = TABULAR,
    ),
)

val LocalScoreTypography = staticCompositionLocalOf { ScoreTypography() }
