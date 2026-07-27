package se.linusborjesson.scorespeaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.settings.AppLanguage
import se.linusborjesson.scorespeaker.settings.AppSettings
import se.linusborjesson.scorespeaker.settings.OffsetStyle
import se.linusborjesson.scorespeaker.settings.Verbosity
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme
import kotlin.math.roundToInt

/**
 * One row in the Key-bindings group. Platform-neutral: the key label is
 * pre-formatted by the platform (it knows its key codes), and [listening]
 * marks the row currently waiting for a key press.
 */
data class KeyBindingRowUi(
    val actionId: String,
    val label: String,
    val keyLabel: String?,
    val listening: Boolean,
)

/**
 * Settings — only the controls with real backing (speech rate, what to
 * announce, verbosity, hardware-key bindings). Voice/language/mm-units from
 * the design are omitted until they do something.
 *
 * Landscape shows two independently scrolling columns; portrait stacks all
 * groups in one scrolling column.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    /** Show the Developer group. Android passes BuildConfig.DEBUG. */
    showDeveloper: Boolean = true,
    /** Key-binding rows; empty hides the group (e.g. desktop harness). */
    keyBindings: List<KeyBindingRowUi> = emptyList(),
    /** Tap on a binding row — toggles listening for that action. */
    onKeyBindingClick: (String) -> Unit = {},
    /** Clear a row's binding. */
    onKeyBindingClear: (String) -> Unit = {},
    /** Opens the privacy policy (Android: browser intent). Null hides the row. */
    onPrivacyPolicyClick: (() -> Unit)? = null,
    /** Opens the bundled third-party notices. Null hides the row. */
    onLicensesClick: (() -> Unit)? = null,
) {
    val c = ScoreTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.padding(start = 18.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScoreSpeakerMark(Modifier.size(24.dp))
            Text(LocalStrings.current.settingsTitle, style = ScoreTheme.type.titleLarge, color = c.textHi)
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 12.dp)) {
            if (maxWidth < maxHeight) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    CameraGroup(settings, onChange)
                    SpeechGroup(settings, onChange)
                    AnnouncementsGroup(settings, onChange)
                    if (keyBindings.isNotEmpty()) {
                        KeyBindingsGroup(keyBindings, onKeyBindingClick, onKeyBindingClear)
                    }
                    AboutGroup(onPrivacyPolicyClick, onLicensesClick)
                    if (showDeveloper) DeveloperGroup(settings, onChange)
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        CameraGroup(settings, onChange)
                        SpeechGroup(settings, onChange)
                        if (showDeveloper) DeveloperGroup(settings, onChange)
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        AnnouncementsGroup(settings, onChange)
                        if (keyBindings.isNotEmpty()) {
                            KeyBindingsGroup(keyBindings, onKeyBindingClick, onKeyBindingClear)
                        }
                        AboutGroup(onPrivacyPolicyClick, onLicensesClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraGroup(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val c = ScoreTheme.colors
    val s = LocalStrings.current
    // map 1.0..5.0 → 0..1 for the slider
    fun zoomToSlider(z: Float) = ((z - 1f) / 4f).coerceIn(0f, 1f)
    fun sliderToZoom(v: Float) = 1f + v * 4f

    Group(s.camera) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(s.cameraZoom, style = ScoreTheme.type.body, color = c.textHi)
                Spacer(Modifier.weight(1f))
                Text("${"%.1f".format(settings.zoomRatio)}×", style = ScoreTheme.type.stat, color = c.good)
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = zoomToSlider(settings.zoomRatio),
                onChange = { onChange(settings.copy(zoomRatio = (sliderToZoom(it) * 10).roundToInt() / 10f)) },
                label = s.cameraZoom,
                valueDescription = "${"%.1f".format(settings.zoomRatio)}×",
            )
            Spacer(Modifier.height(6.dp))
            Text(s.cameraZoomSub, style = ScoreTheme.type.caption, color = c.textMid)
        }
    }
}

@Composable
private fun SpeechGroup(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val c = ScoreTheme.colors
    val s = LocalStrings.current
    // map 0.5..2.0 → 0..1 for the slider
    fun rateToSlider(r: Float) = ((r - 0.5f) / 1.5f).coerceIn(0f, 1f)
    fun sliderToRate(v: Float) = (0.5f + v * 1.5f)

    Group(s.voiceAndSpeech) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(s.speechRate, style = ScoreTheme.type.body, color = c.textHi)
                Spacer(Modifier.weight(1f))
                Text("${"%.1f".format(settings.speechRate)}×", style = ScoreTheme.type.stat, color = c.good)
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = rateToSlider(settings.speechRate),
                onChange = { onChange(settings.copy(speechRate = (sliderToRate(it) * 10).roundToInt() / 10f)) },
                label = s.speechRate,
                valueDescription = s.speechRateTimesTemplate.format("%.1f".format(settings.speechRate)),
            )
        }
        Divider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(s.language, style = ScoreTheme.type.body, color = c.textHi)
            Spacer(Modifier.height(8.dp))
            // Mapping by position — localized labels can't key the lookup.
            val labels = listOf(s.languageSystem, s.languageEnglish, s.languageSwedish)
            val order = listOf(AppLanguage.SYSTEM, AppLanguage.ENGLISH, AppLanguage.SWEDISH)
            Segmented(
                options = labels,
                active = labels[order.indexOf(settings.language)],
                onSelect = { label ->
                    onChange(settings.copy(language = order[labels.indexOf(label)]))
                },
            )
        }
    }
}

@Composable
private fun KeyBindingsGroup(
    rows: List<KeyBindingRowUi>,
    onClick: (String) -> Unit,
    onClear: (String) -> Unit,
) {
    val c = ScoreTheme.colors
    val s = LocalStrings.current
    Group(s.keyBindings) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Divider()
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClick = { onClick(row.actionId) })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.label, style = ScoreTheme.type.body, color = c.textHi)
                    Text(
                        when {
                            row.listening -> s.bindingListeningSub
                            row.keyLabel != null -> row.keyLabel
                            else -> s.bindingNotBoundSub
                        },
                        style = ScoreTheme.type.caption,
                        color = if (row.listening) c.guide else c.textMid,
                    )
                }
                when {
                    row.listening -> Chip(s.listeningChip, tone = ChipTone.Info)
                    row.keyLabel != null -> Box(
                        Modifier.clickable(role = Role.Button, onClick = { onClear(row.actionId) })
                            .semantics { contentDescription = s.clearBindingForTemplate.format(row.label) },
                    ) { Chip(s.clearChip, tone = ChipTone.Neutral) }
                }
            }
        }
    }
}

@Composable
private fun AboutGroup(
    onPrivacyPolicyClick: (() -> Unit)?,
    onLicensesClick: (() -> Unit)?,
) {
    if (onPrivacyPolicyClick == null && onLicensesClick == null) return
    val s = LocalStrings.current
    Group(s.about) {
        onPrivacyPolicyClick?.let {
            LinkRow(s.privacyPolicy, s.privacyPolicySub, it)
        }
        if (onPrivacyPolicyClick != null && onLicensesClick != null) Divider()
        onLicensesClick?.let {
            LinkRow(s.ossLicenses, s.ossLicensesSub, it)
        }
    }
}

@Composable
private fun LinkRow(title: String, sub: String, onClick: () -> Unit) {
    val c = ScoreTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = ScoreTheme.type.body, color = c.textHi)
        Spacer(Modifier.height(2.dp))
        Text(sub, style = ScoreTheme.type.caption, color = c.textMid)
    }
}

@Composable
private fun DeveloperGroup(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val s = LocalStrings.current
    Group(s.developer) {
        ToggleRow(s.debugOverlays, settings.debugOverlay, s.debugOverlaysSub) {
            onChange(settings.copy(debugOverlay = it))
        }
        Divider()
        ToggleRow(s.autoCaptureShots, settings.debugAutoCapture, s.autoCaptureShotsSub) {
            onChange(settings.copy(debugAutoCapture = it))
        }
    }
}

@Composable
private fun AnnouncementsGroup(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val c = ScoreTheme.colors
    val s = LocalStrings.current
    Group(s.announcements) {
        ToggleRow(s.scoreAfterEachShot, settings.announceScore) {
            onChange(settings.copy(announceScore = it))
        }
        Divider()
        ToggleRow(s.aimOffset, settings.announceOffset, s.aimOffsetSub) {
            onChange(settings.copy(announceOffset = it))
        }
        ToggleRow(s.misses, settings.announceMisses, s.missesSub) {
            onChange(settings.copy(announceMisses = it))
        }
        Divider()
        // Decimals only affect the directions style — the clock call carries
        // no number — so the row greys out under clock.
        ToggleRow(
            s.offsetDecimals, settings.offsetDecimals, s.offsetDecimalsSub,
            enabled = settings.offsetStyle == OffsetStyle.DIRECTIONS,
        ) {
            onChange(settings.copy(offsetDecimals = it))
        }
        Divider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(s.offsetStyle, style = ScoreTheme.type.body, color = c.textHi)
            Spacer(Modifier.height(8.dp))
            val styleLabels = listOf(s.offsetStyleDirections, s.offsetStyleClock)
            val styleOrder = listOf(OffsetStyle.DIRECTIONS, OffsetStyle.CLOCK)
            Segmented(
                options = styleLabels,
                active = styleLabels[styleOrder.indexOf(settings.offsetStyle)],
                onSelect = { label ->
                    onChange(settings.copy(offsetStyle = styleOrder[styleLabels.indexOf(label)]))
                },
            )
        }
        Divider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(s.verbosity, style = ScoreTheme.type.body, color = c.textHi)
            Spacer(Modifier.height(8.dp))
            // Localized labels can't key the reverse mapping — pair by
            // position instead (order matches the Verbosity entries).
            val labels = listOf(s.verbosityTerse, s.verbosityNormal)
            val order = listOf(Verbosity.TERSE, Verbosity.NORMAL)
            Segmented(
                options = labels,
                active = labels[order.indexOf(settings.verbosity)],
                onSelect = { label ->
                    onChange(settings.copy(verbosity = order[labels.indexOf(label)]))
                },
            )
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Eyebrow(title, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        InstrumentCard(Modifier.fillMaxWidth()) { Column { content() } }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    on: Boolean,
    sub: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val c = ScoreTheme.colors
    // The whole row is the switch: one TalkBack element carrying the title,
    // subtitle, and on/off state; the pill itself is decorative. Disabled
    // rows grey out and TalkBack announces them as disabled (toggleable's
    // enabled flag).
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ScoreTheme.type.body, color = c.textHi)
            sub?.let { Text(it, style = ScoreTheme.type.caption, color = c.textMid) }
        }
        Toggle(on, onChange = null)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ScoreTheme.colors.line))
}
