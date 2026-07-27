package se.linusborjesson.scorespeaker

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.pipeline.Session
import se.linusborjesson.scorespeaker.settings.AppSettings
import se.linusborjesson.scorespeaker.ui.Chip
import se.linusborjesson.scorespeaker.ui.ChipTone
import se.linusborjesson.scorespeaker.ui.HistoryScreen
import se.linusborjesson.scorespeaker.ui.KeyBindingRowUi
import se.linusborjesson.scorespeaker.ui.LocalStrings
import se.linusborjesson.scorespeaker.ui.SessionDetailScreen
import se.linusborjesson.scorespeaker.ui.uiStrings
import se.linusborjesson.scorespeaker.ui.SettingsScreen
import se.linusborjesson.scorespeaker.ui.theme.ScoreSpeakerTheme

private enum class Screen { LIVE, HISTORY, SETTINGS, LICENSES }

/**
 * App shell — the live camera screen is home; its History / Settings
 * chips push the other screens on top. System back (and an overlaid BACK chip)
 * returns to live. History reads the shot DB each time it's opened (sessions
 * are derived fresh); Settings edits the persisted [AppSettings].
 */
@Composable
fun AppRoot(
    live: LiveUiState,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    keyBindings: List<KeyBindingRowUi>,
    onKeyBindingClick: (String) -> Unit,
    onKeyBindingClear: (String) -> Unit,
    onPreviewReady: (PreviewView) -> Unit,
    onCaptureClick: () -> Unit,
    onZoomGesture: (Float) -> Unit,
    loadSessions: () -> List<Session>,
    now: () -> Long,
    onPrivacyPolicyClick: () -> Unit,
    loadLicensesText: () -> String,
) {
    var screen by remember { mutableStateOf(Screen.LIVE) }
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }

    // Refresh sessions whenever History is (re)opened.
    LaunchedEffect(screen) {
        if (screen == Screen.HISTORY) sessions = loadSessions()
    }

    // UI language: the setting resolves against the system locale (SYSTEM
    // follows it, ENGLISH/SWEDISH force). Changing the setting recomposes
    // with the new strings immediately.
    val strings = remember(settings.language) {
        uiStrings(settings.language.resolve(java.util.Locale.getDefault().language))
    }
    val formatter = remember(strings) { HistoryFormatter(strings) }

    CompositionLocalProvider(LocalStrings provides strings) {
    ScoreSpeakerTheme {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.LIVE -> CameraScreen(
                    state = live,
                    onHistoryClick = { screen = Screen.HISTORY },
                    onSettingsClick = { screen = Screen.SETTINGS },
                    onPreviewReady = onPreviewReady,
                    onCaptureClick = onCaptureClick,
                    onZoomGesture = onZoomGesture,
                )
                Screen.HISTORY -> InsetScreen {
                    val idx = detailIndex
                    if (idx == null) {
                        HistoryScreen(
                            state = formatter.build(sessions, now()),
                            onSessionClick = { detailIndex = it },
                        )
                        BackChip { screen = Screen.LIVE }
                        BackHandler { screen = Screen.LIVE }
                    } else {
                        val session = sessions.firstOrNull { it.index == idx }
                        if (session == null) {
                            detailIndex = null
                        } else {
                            SessionDetailScreen(
                                state = formatter.detail(session),
                                onBack = { detailIndex = null },
                            )
                            BackHandler { detailIndex = null }
                        }
                    }
                }
                Screen.SETTINGS -> InsetScreen {
                    SettingsScreen(
                        settings, onSettingsChange,
                        showDeveloper = BuildConfig.DEBUG,
                        keyBindings = keyBindings,
                        onKeyBindingClick = onKeyBindingClick,
                        onKeyBindingClear = onKeyBindingClear,
                        onPrivacyPolicyClick = onPrivacyPolicyClick,
                        onLicensesClick = { screen = Screen.LICENSES },
                    )
                    BackChip { screen = Screen.LIVE }
                    BackHandler { screen = Screen.LIVE }
                }
                Screen.LICENSES -> InsetScreen {
                    val licensesText = remember { loadLicensesText() }
                    se.linusborjesson.scorespeaker.ui.LicensesScreen(licensesText)
                    BackChip { screen = Screen.SETTINGS }
                    BackHandler { screen = Screen.SETTINGS }
                }
            }
        }
    }
    }
}

/**
 * Full-size container that keeps a screen clear of the transparent system
 * bars (targetSdk 35 enforces edge-to-edge; without this the nav bar
 * overlays the content). The themed root background still bleeds behind the
 * bars, so the edges look intentional. The live camera screen manages its
 * own insets instead — the feed should be full-bleed.
 */
@Composable
private fun InsetScreen(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        content = content,
    )
}

/** Overlaid top-right BACK chip for screens that have no back affordance of their own. */
@Composable
private fun BoxScope.BackChip(onBack: () -> Unit) {
    Box(
        Modifier.align(Alignment.TopEnd).padding(12.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onBack),
    ) { Chip(LocalStrings.current.backChip, tone = ChipTone.Neutral) }
}
