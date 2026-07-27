package se.linusborjesson.scorespeaker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.linusborjesson.scorespeaker.testdata.TestCase
import java.io.File

/** Left sidebar: the corpus case list with import/refresh actions. */
@Composable
internal fun TestCaseSidebar(
    testDataDir: File,
    testCases: List<TestCase>,
    selectedCaseId: String?,
    onSelect: (TestCase) -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.width(280.dp).fillMaxHeight(),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Test Data", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = testDataDir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Toolbar
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImport,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Import")
                }
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            HorizontalDivider()

            // Item list
            if (testCases.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No test data found.\nClick 'Import' to add images.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(testCases) { case ->
                        TestCaseRow(
                            case = case,
                            isSelected = case.id == selectedCaseId,
                            onClick = { onSelect(case) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TestCaseRow(
    case: TestCase,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val annotations = remember(case.id) { case.loadAnnotations() }
    val hasCorners = annotations.screenCorners != null
    val expectedCount = annotations.expectedValues.size

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                case.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasCorners) {
                    Text("✓ corners", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.primary)
                }
                if (expectedCount > 0) {
                    Text("$expectedCount values", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
