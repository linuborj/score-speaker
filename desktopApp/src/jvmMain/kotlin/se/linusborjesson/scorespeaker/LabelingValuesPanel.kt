package se.linusborjesson.scorespeaker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.cells.CellTypes
import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.cells.ScoreValue
import se.linusborjesson.scorespeaker.cells.TextValue

/**
 * Right-hand panel (EditValues mode): one input per labelable cell, writing
 * into the expected-values map. Blank input removes the cell's entry.
 */
@Composable
internal fun LabelingValuesPanel(
    cellNames: List<String>,
    expectedValues: Map<String, CellValue>,
    onValuesChange: (Map<String, CellValue>) -> Unit,
) {
    Surface(
        modifier = Modifier.width(280.dp).fillMaxHeight(),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Expected Values",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Enter the expected reading for each cell",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            cellNames.forEach { cellName ->
                val textValue = when (val v = expectedValues[cellName]) {
                    is TextValue -> v.text
                    is ScoreValue -> v.value
                    else -> ""
                }
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { newValue ->
                        onValuesChange(
                            if (newValue.isBlank()) {
                                expectedValues - cellName
                            } else {
                                val cellValue = CellTypes.createValue(cellName, newValue)
                                if (cellValue != null) {
                                    expectedValues + (cellName to cellValue)
                                } else {
                                    expectedValues - cellName
                                }
                            }
                        )
                    },
                    label = { Text("Cell $cellName") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    }
}
