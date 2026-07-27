package se.linusborjesson.scorespeaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.ui.theme.ScoreTheme

/**
 * Third-party open-source notices, rendered from the bundled plain-text
 * asset. Plain scrollable text — TalkBack reads it as-is.
 */
@Composable
fun LicensesScreen(text: String) {
    val c = ScoreTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.padding(start = 18.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScoreSpeakerMark(Modifier.size(24.dp))
            Text(LocalStrings.current.ossLicenses, style = ScoreTheme.type.titleLarge, color = c.textHi)
        }
        Text(
            text,
            style = ScoreTheme.type.body,
            color = c.textMid,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
        )
    }
}
