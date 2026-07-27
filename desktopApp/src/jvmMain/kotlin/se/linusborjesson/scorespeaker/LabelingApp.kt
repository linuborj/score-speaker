package se.linusborjesson.scorespeaker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.linusborjesson.scorespeaker.pipeline.*
import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.testdata.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

enum class LabelingMode {
    View,
    MarkCorners,
    EditValues
}

/**
 * The labeling GUI: state + pipeline actions live here; the four UI regions
 * are separate composables (sidebar, toolbar, image view, values panel)
 * that receive state and send events back up.
 */
@Composable
fun LabelingApp() {
    val testDataDir = remember { findTestDataDir() }
    val corpusDir = remember { findCorpusDir() }

    var testCases by remember { mutableStateOf<List<TestCase>>(emptyList()) }
    var selectedCase by remember { mutableStateOf<TestCase?>(null) }
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var sourceImage by remember { mutableStateOf<BufferedImage?>(null) }
    var annotations by remember { mutableStateOf(Annotations()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Labeling state
    var labelingMode by remember { mutableStateOf(LabelingMode.View) }
    var corners by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var expectedValues by remember { mutableStateOf<Map<String, CellValue>>(emptyMap()) }

    // The annotatable cells, in layout order.
    val cellNames = remember {
        CellLayout.siusDisplayCells().map { it.name }.filter { it in LABEL_CELLS }
    }

    // Detector for auto-detection
    val detector = remember { createDetector(testDataDir) }

    val scope = rememberCoroutineScope()

    // Refresh test cases
    fun refreshTestCases() {
        testCases = TestCase.findAll(corpusDir)
    }

    // Load test cases on startup
    LaunchedEffect(Unit) {
        refreshTestCases()
    }

    // Track which case the current image belongs to (plain object, not Compose state)
    val imageLoadState = remember { object {
        var caseId: String? = null
        var isLoaded: Boolean = false
    } }

    // Load image and annotations when case is selected
    LaunchedEffect(selectedCase) {
        selectedCase?.let { case ->
            // Skip reload if image is already loaded for this case
            if (imageLoadState.caseId == case.id && imageLoadState.isLoaded) {
                return@LaunchedEffect
            }

            isLoading = true
            errorMessage = null
            try {
                val (bitmap, image, loadedAnnotations) = withContext(Dispatchers.IO) {
                    val bufferedImage = ImageIO.read(case.sourceFile)
                    val ann = case.loadAnnotations()
                    Triple(
                        bufferedImage?.toComposeImageBitmap(),
                        bufferedImage,
                        ann
                    )
                }
                if (bitmap != null) {
                    imageBitmap = bitmap
                    sourceImage = image
                    annotations = loadedAnnotations
                    imageLoadState.caseId = case.id
                    imageLoadState.isLoaded = true

                    // Load corners from annotations
                    corners = loadedAnnotations.screenCorners?.toList()?.map {
                        Offset(it.x.toFloat(), it.y.toFloat())
                    } ?: emptyList()

                    // Load expected values
                    expectedValues = loadedAnnotations.expectedValues
                } else {
                    errorMessage = "Could not decode image"
                    imageBitmap = null
                    sourceImage = null
                    imageLoadState.caseId = null
                    imageLoadState.isLoaded = false
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load image: ${e.message}"
                imageBitmap = null
                sourceImage = null
                imageLoadState.caseId = null
                imageLoadState.isLoaded = false
            }
            isLoading = false
        } ?: run {
            imageBitmap = null
            sourceImage = null
            corners = emptyList()
            expectedValues = emptyMap()
            annotations = Annotations()
            imageLoadState.caseId = null
            imageLoadState.isLoaded = false
        }
    }

    // Auto-detect corners
    fun autoDetect() {
        val image = sourceImage ?: return
        val det = detector ?: return

        scope.launch {
            isLoading = true
            statusMessage = "Detecting screen..."
            try {
                val detected = withContext(Dispatchers.IO) {
                    val source = ImageSource.fromBufferedImage(image, sourcePath = selectedCase?.sourceFile?.absolutePath)
                    det.detect(source)
                }
                if (detected != null) {
                    corners = listOf(
                        Offset(detected.screenQuad.topLeft.x.toFloat(), detected.screenQuad.topLeft.y.toFloat()),
                        Offset(detected.screenQuad.topRight.x.toFloat(), detected.screenQuad.topRight.y.toFloat()),
                        Offset(detected.screenQuad.bottomRight.x.toFloat(), detected.screenQuad.bottomRight.y.toFloat()),
                        Offset(detected.screenQuad.bottomLeft.x.toFloat(), detected.screenQuad.bottomLeft.y.toFloat())
                    )
                    statusMessage = "Detected! Confidence: ${String.format("%.2f", detected.confidence)}"
                } else {
                    statusMessage = "Detection failed - mark corners manually"
                }
            } catch (e: Exception) {
                statusMessage = "Detection error: ${e.message}"
            }
            isLoading = false
        }
    }

    // Save corners to annotations
    fun saveCorners() {
        if (corners.size != 4) return
        selectedCase?.let { case ->
            val screenCorners = ScreenCorners(
                topLeft = JsonPoint(corners[0].x.toDouble(), corners[0].y.toDouble()),
                topRight = JsonPoint(corners[1].x.toDouble(), corners[1].y.toDouble()),
                bottomRight = JsonPoint(corners[2].x.toDouble(), corners[2].y.toDouble()),
                bottomLeft = JsonPoint(corners[3].x.toDouble(), corners[3].y.toDouble())
            )
            annotations = annotations.copy(screenCorners = screenCorners)
            case.saveAnnotations(annotations)
            statusMessage = "Corners saved"
        }
    }

    // Save expected values to annotations
    fun saveExpectedValues() {
        selectedCase?.let { case ->
            annotations = annotations.copy(expectedValues = expectedValues)
            case.saveAnnotations(annotations)
            statusMessage = "Expected values saved (${expectedValues.size} cells)"
        }
    }

    val importLauncher = rememberFilePickerLauncher(
        type = FileKitType.Image,
        onResult = { file: PlatformFile? ->
            if (file != null) {
                scope.launch {
                    isLoading = true
                    errorMessage = null
                    statusMessage = "Importing..."
                    try {
                        val bytes = file.readBytes()
                        val (newCase, image) = withContext(Dispatchers.IO) {
                            // Read image to validate and normalize
                            val img = ImageIO.read(ByteArrayInputStream(bytes))
                                ?: throw IllegalArgumentException("Could not decode image")

                            // Create new test case with UUID folder, original filename in metadata
                            val case = TestCase.create(corpusDir, file.name)
                            ImageIO.write(img, "png", case.sourceFile)
                            Pair(case, img)
                        }

                        // Set image state directly to avoid reloading
                        imageBitmap = image.toComposeImageBitmap()
                        sourceImage = image
                        annotations = Annotations()
                        corners = emptyList()
                        expectedValues = emptyMap()
                        imageLoadState.caseId = newCase.id
                        imageLoadState.isLoaded = true

                        refreshTestCases()
                        selectedCase = newCase
                        statusMessage = "Imported: ${newCase.displayName}"

                        // Auto-detect on import
                        autoDetect()
                    } catch (e: Exception) {
                        errorMessage = "Failed to import image: ${e.message}"
                    }
                    isLoading = false
                }
            }
        }
    )

    MaterialTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            TestCaseSidebar(
                testDataDir = corpusDir,
                testCases = testCases,
                selectedCaseId = selectedCase?.id,
                onSelect = { selectedCase = it },
                onImport = { importLauncher.launch() },
                onRefresh = { refreshTestCases() },
            )

            // Main content area
            Column(modifier = Modifier.fillMaxSize()) {
                LabelingToolbar(
                    selectedCase = selectedCase,
                    statusMessage = statusMessage,
                    hasImage = imageBitmap != null,
                    isLoading = isLoading,
                    labelingMode = labelingMode,
                    onModeChange = { labelingMode = it },
                    cornersCount = corners.size,
                    onClearCorners = { corners = emptyList() },
                    onSaveCorners = {
                        saveCorners()
                        labelingMode = LabelingMode.View
                    },
                    valuesCount = expectedValues.size,
                    onClearValues = { expectedValues = emptyMap() },
                    onSaveValues = {
                        saveExpectedValues()
                        labelingMode = LabelingMode.View
                    },
                    canAutoDetect = detector != null,
                    onAutoDetect = { autoDetect() },
                )

                // Image display area (with optional values panel)
                Row(modifier = Modifier.fillMaxSize()) {
                    LabelingImageView(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        errorMessage = errorMessage,
                        imageBitmap = imageBitmap,
                        isLoading = isLoading,
                        labelingMode = labelingMode,
                        corners = corners,
                        onCornersChange = { corners = it },
                        expectedValues = expectedValues,
                    )

                    if (labelingMode == LabelingMode.EditValues && imageBitmap != null) {
                        LabelingValuesPanel(
                            cellNames = cellNames,
                            expectedValues = expectedValues,
                            onValuesChange = { expectedValues = it },
                        )
                    }
                }
            }
        }
    }
}
