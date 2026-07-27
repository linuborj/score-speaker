package se.linusborjesson.scorespeaker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.linusborjesson.scorespeaker.processing.MatchResult
import se.linusborjesson.scorespeaker.processing.OrbTemplateMatcher
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

@Composable
fun TemplateMatchingApp() {
    var templateName by remember { mutableStateOf<String?>(null) }
    var templateImage by remember { mutableStateOf<BufferedImage?>(null) }
    var templateBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    var targetName by remember { mutableStateOf<String?>(null) }
    var targetImage by remember { mutableStateOf<BufferedImage?>(null) }
    var targetBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    var matchResult by remember { mutableStateOf<MatchResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Load a template image to begin") }

    val scope = rememberCoroutineScope()

    // The template matcher
    val matcher = remember { OrbTemplateMatcher() }
    var templateLoaded by remember { mutableStateOf(false) }

    // Load template when template image changes
    LaunchedEffect(templateImage) {
        templateImage?.let { image ->
            isProcessing = true
            statusMessage = "Loading template..."
            withContext(Dispatchers.IO) {
                try {
                    val templateMat = se.linusborjesson.scorespeaker.pipeline.ImageBridge.toMat(image)
                    matcher.loadTemplate(templateMat)
                    templateMat.release()
                    templateLoaded = true
                    templateBitmap = matcher.getTemplateMat()
                        ?.let(se.linusborjesson.scorespeaker.pipeline.ImageBridge::toBufferedImage)
                        ?.toComposeImageBitmap()
                } catch (e: Exception) {
                    e.printStackTrace()
                    statusMessage = "Error loading template: ${e.message}"
                    templateLoaded = false
                }
            }
            if (templateLoaded) {
                statusMessage = "Template loaded. Load a target image to match."
            }
            isProcessing = false
        }
    }

    // Run matching when target image changes (and template is loaded)
    LaunchedEffect(targetImage, templateLoaded) {
        if (templateLoaded && targetImage != null) {
            isProcessing = true
            statusMessage = "Matching..."
            withContext(Dispatchers.IO) {
                try {
                    val targetMat = se.linusborjesson.scorespeaker.pipeline.ImageBridge.toMat(targetImage!!)
                    matchResult = try { matcher.matchAndRefine(targetMat) } finally { targetMat.release() }
                } catch (e: Exception) {
                    e.printStackTrace()
                    statusMessage = "Error: ${e.message}"
                }
            }
            matchResult?.let { result ->
                statusMessage = if (result.success) {
                    "Match found! ${result.matchCount} matches, ${result.inlierCount} inliers"
                } else {
                    "No match. ${result.matchCount} matches, ${result.inlierCount} inliers"
                }
            }
            isProcessing = false
        }
    }

    val templatePicker = rememberFilePickerLauncher(
        type = FileKitType.Image,
    ) { file: PlatformFile? ->
        file?.let {
            scope.launch {
                templateName = file.name
                val bytes = file.readBytes()
                withContext(Dispatchers.IO) {
                    val image = ImageIO.read(ByteArrayInputStream(bytes))
                    templateImage = image
                }
            }
        }
    }

    val targetPicker = rememberFilePickerLauncher(
        type = FileKitType.Image,
    ) { file: PlatformFile? ->
        file?.let {
            scope.launch {
                targetName = file.name
                val bytes = file.readBytes()
                withContext(Dispatchers.IO) {
                    val image = ImageIO.read(ByteArrayInputStream(bytes))
                    targetImage = image
                    targetBitmap = image.toComposeImageBitmap()
                }
            }
        }
    }

    MaterialTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            // Control panel
            Surface(
                modifier = Modifier.width(320.dp).fillMaxHeight(),
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Template Matching", style = MaterialTheme.typography.titleLarge)

                    // Template loading
                    Text("Template", style = MaterialTheme.typography.titleMedium)

                    Button(
                        onClick = { templatePicker.launch() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Load Template")
                    }

                    templateName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Template preview
                    templateBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Template",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                                .background(Color.Black),
                            contentScale = ContentScale.Fit
                        )
                    }

                    HorizontalDivider()

                    // Target loading
                    Text("Target Image", style = MaterialTheme.typography.titleMedium)

                    Button(
                        onClick = { targetPicker.launch() },
                        enabled = templateLoaded,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Load Target")
                    }

                    targetName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        targetImage?.let { img ->
                            Text(
                                text = "${img.width} × ${img.height}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider()

                    // Status
                    Text(statusMessage, style = MaterialTheme.typography.bodyMedium)

                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }

                    HorizontalDivider()

                    // Results
                    Text("Match Results", style = MaterialTheme.typography.titleMedium)

                    matchResult?.let { result ->
                        Text("Matches: ${result.matchCount}")
                        Text("Inliers: ${result.inlierCount}")
                        Text("Success: ${result.success}")

                        val corners = result.corners
                        if (result.success && corners != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Detected Corners:", style = MaterialTheme.typography.titleSmall)
                            val labels = listOf("Top-Left", "Top-Right", "Bottom-Right", "Bottom-Left")
                            corners.forEachIndexed { i, (x, y) ->
                                Text("  ${labels[i]}: (${x.toInt()}, ${y.toInt()})")
                            }
                        }
                    }
                }
            }

            // Image display
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                if (targetBitmap != null) {
                    MatchOverlayImage(
                        bitmap = targetBitmap!!,
                        matchResult = matchResult,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        if (templateLoaded) "Load a target image to match"
                        else "Load a template first",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchOverlayImage(
    bitmap: ImageBitmap,
    matchResult: MatchResult?,
    modifier: Modifier = Modifier
) {
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier.onSizeChanged { layoutSize = it },
        contentAlignment = Alignment.Center
    ) {
        // Calculate how image fits in layout
        val imageAspect = bitmap.width.toFloat() / bitmap.height
        val layoutAspect = if (layoutSize.height > 0) layoutSize.width.toFloat() / layoutSize.height else 1f

        val (fitWidth, fitHeight) = if (imageAspect > layoutAspect) {
            layoutSize.width.toFloat() to layoutSize.width / imageAspect
        } else {
            layoutSize.height * imageAspect to layoutSize.height.toFloat()
        }

        val offsetX = (layoutSize.width - fitWidth) / 2
        val offsetY = (layoutSize.height - fitHeight) / 2
        val scale = fitWidth / bitmap.width

        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Draw match overlay
        if (matchResult != null && layoutSize.width > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                fun imageToScreen(x: Double, y: Double): Offset {
                    return Offset(
                        (offsetX + x * scale).toFloat(),
                        (offsetY + y * scale).toFloat()
                    )
                }

                // Draw matched feature points
                matchResult.matchedPoints?.take(30)?.forEach { (_, imagePt) ->
                    drawCircle(
                        color = Color.Cyan.copy(alpha = 0.5f),
                        radius = 4f,
                        center = imageToScreen(imagePt.x, imagePt.y)
                    )
                }

                // Draw detected quadrilateral
                val corners = matchResult.corners
                if (matchResult.success && corners != null) {
                    val screenCorners = corners.map { (x, y) -> imageToScreen(x, y) }

                    // Draw edges
                    for (i in screenCorners.indices) {
                        val start = screenCorners[i]
                        val end = screenCorners[(i + 1) % 4]
                        drawLine(
                            color = Color.Green,
                            start = start,
                            end = end,
                            strokeWidth = 3f
                        )
                    }

                    // Draw corner points
                    val cornerColors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
                    screenCorners.forEachIndexed { i, corner ->
                        drawCircle(
                            color = cornerColors[i],
                            radius = 12f,
                            center = corner
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 12f,
                            center = corner,
                            style = Stroke(width = 2f)
                        )
                    }
                }
            }
        }
    }
}
