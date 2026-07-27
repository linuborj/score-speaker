package se.linusborjesson.scorespeaker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.testdata.TestCase

/**
 * Top action bar: case name + status on the left, mode-dependent actions on
 * the right (View's detection/overlay toggles, or the Save/Clear pair while
 * marking corners / editing values).
 */
@Composable
internal fun LabelingToolbar(
    selectedCase: TestCase?,
    statusMessage: String?,
    hasImage: Boolean,
    isLoading: Boolean,
    labelingMode: LabelingMode,
    onModeChange: (LabelingMode) -> Unit,
    cornersCount: Int,
    onClearCorners: () -> Unit,
    onSaveCorners: () -> Unit,
    valuesCount: Int,
    onClearValues: () -> Unit,
    onSaveValues: () -> Unit,
    canAutoDetect: Boolean,
    onAutoDetect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status
            Column(modifier = Modifier.weight(1f)) {
                selectedCase?.let { case ->
                    Text(case.displayName, style = MaterialTheme.typography.titleSmall)
                    statusMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } ?: Text(
                    "Select an item from the list",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Actions
            if (hasImage) {
                when (labelingMode) {
                    LabelingMode.MarkCorners -> {
                        Text(
                            if (cornersCount < 4) "Click: ${CORNER_LABELS[cornersCount]}" else "Adjust corners",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedButton(onClick = onClearCorners) {
                            Text("Clear")
                        }
                        Button(onClick = onSaveCorners) {
                            Text("Save")
                        }
                    }
                    LabelingMode.EditValues -> {
                        Text(
                            "$valuesCount values",
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedButton(onClick = onClearValues) {
                            Text("Clear")
                        }
                        Button(onClick = onSaveValues) {
                            Text("Save")
                        }
                    }
                    LabelingMode.View -> {
                        if (canAutoDetect) {
                            OutlinedButton(
                                onClick = onAutoDetect,
                                enabled = !isLoading
                            ) {
                                Text("Auto-Detect")
                            }
                        }
                        OutlinedButton(onClick = { onModeChange(LabelingMode.MarkCorners) }) {
                            Text("Corners${if (cornersCount == 4) " ✓" else ""}")
                        }
                        OutlinedButton(
                            onClick = { onModeChange(LabelingMode.EditValues) },
                            enabled = cornersCount == 4
                        ) {
                            Text("Values")
                        }
                    }
                }
            }
        }
    }
}
