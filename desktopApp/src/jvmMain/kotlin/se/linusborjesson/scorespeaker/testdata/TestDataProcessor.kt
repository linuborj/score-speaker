package se.linusborjesson.scorespeaker.testdata

import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.ocr.DigitTemplateMatcher
import se.linusborjesson.scorespeaker.pipeline.extractCell
import se.linusborjesson.scorespeaker.pipeline.fromBufferedImage
import se.linusborjesson.scorespeaker.pipeline.*
import se.linusborjesson.scorespeaker.pipeline.ImageBridge
import se.linusborjesson.scorespeaker.processing.MultiScaleMatcher
import se.linusborjesson.scorespeaker.processing.loadAlphabetFromDirectory
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * Processes test cases through the detection pipeline.
 */
class TestDataProcessor(
    private val templateFile: File,
    private val debug: Boolean = false,
    private val enableOcr: Boolean = false
) {
    private val detector: ScreenDetector
    private val cellDExtractor: se.linusborjesson.scorespeaker.ocr.CellDExtractor?

    init {
        val templateImage = ImageIO.read(templateFile)
        detector = MultiScaleMatcher(
            scales = listOf(0.3, 0.5, 0.7, 1.0),
            maxFeatures = 500,
            minInliers = 8,
            maxInputWidth = 2500
        )
        detector.loadTemplate(ImageBridge.toMat(templateImage))

        cellDExtractor = if (enableOcr) {
            // The production reader: real-glyph matching against the
            // harvested shot-font alphabet next to the corpus.
            val glyphDir = templateFile.parentFile?.let { File(it, "glyphs/shot-font") }
            val matcher = DigitTemplateMatcher(minScore = 0.5)
                .takeIf { glyphDir != null && it.loadAlphabetFromDirectory(glyphDir) }
            if (matcher != null) {
                println("Reader: shot-font glyph alphabet loaded")
                se.linusborjesson.scorespeaker.ocr.CellDExtractor().apply { shotTemplateMatcher = matcher }
            } else {
                println("Reader: shot-font alphabet not found — Cell D unread")
                null
            }
        } else null
    }

    /**
     * Process a single test case.
     *
     * @param testCase The test case to process
     * @param useExpectedCorners If true, use annotated corners instead of detecting
     * @return The processing result
     */
    fun process(testCase: TestCase, useExpectedCorners: Boolean = false): ProcessingResult {
        println("\n=== Processing: ${testCase.displayName} ===")

        val annotations = testCase.loadAnnotations()
        val sourceImage = ImageIO.read(testCase.sourceFile)
        val source = ImageSource.fromBufferedImage(sourceImage, sourcePath = testCase.sourceFile.absolutePath)

        println("  Source: ${source.width}x${source.height}")

        // Detection phase
        val detectionResult: DetectionResult
        val detectedScreen: DetectedScreen?

        if (useExpectedCorners && annotations.screenCorners != null) {
            println("  Using expected corners (skipping detection)")
            detectedScreen = createDetectedScreenFromCorners(source, annotations.screenCorners)
            detectionResult = DetectionResult(
                success = true,
                method = "manual",
                confidence = 1.0,
                cornersDetected = annotations.screenCorners,
                cornersExpected = annotations.screenCorners,
                cornerErrorPixels = 0.0
            )
        } else {
            val detected = detector.detect(source)
            if (detected != null) {
                detectedScreen = detected
                val cornersDetected = detected.screenQuad.toScreenCorners()
                val cornerError = annotations.screenCorners?.let {
                    calculateCornerError(cornersDetected, it)
                }

                detectionResult = DetectionResult(
                    success = true,
                    method = "MultiScaleMatcher",
                    confidence = detected.confidence.toDouble(),
                    cornersDetected = cornersDetected,
                    cornersExpected = annotations.screenCorners,
                    cornerErrorPixels = cornerError
                )
                println("  Detection: OK (confidence: ${String.format("%.2f", detected.confidence)})")
                cornerError?.let {
                    println("  Corner error: ${String.format("%.1f", it)} pixels")
                }
            } else {
                detectedScreen = null
                detectionResult = DetectionResult(
                    success = false,
                    method = "MultiScaleMatcher",
                    confidence = 0.0,
                    failureReason = "Detection failed"
                )
                println("  Detection: FAILED")
            }
        }

        // Cell extraction phase
        val cellResults = mutableMapOf<String, CellResult>()

        if (detectedScreen != null) {
            val view = detectedScreen.rectifyAtDetectedResolution()
            val layout = view.withSiusCells()

            println("  Rectified: ${view.targetSize.width}x${view.targetSize.height}")

            // Save debug outputs
            if (debug) {
                testCase.outputDir.mkdirs()
                testCase.cellsDir.mkdirs()

                // Save rectified image
                ImageIO.write(ImageBridge.toBufferedImage(view.rectifiedMat), "png", testCase.rectifiedFile)
                println("  Saved: ${testCase.rectifiedFile.name}")

                // Extract and save all cells
                for ((cellName, _) in layout.getAllCellRegions()) {
                    val cellImage = layout.extractCell(cellName)
                    if (cellImage != null) {
                        val cellFile = File(testCase.cellsDir, "$cellName.png")
                        ImageIO.write(cellImage, "png", cellFile)
                    }
                }
                println("  Saved: ${layout.getAllCellRegions().size} cell images")
            }

            // OCR extraction. Only cells with a runtime reader are compared —
            // annotated-but-unread cells (e.g. G, ground truth for the
            // geometric scoring) would otherwise count as permanent misses.
            if (cellDExtractor != null) {
                var extracted: CellValue? = null
                val cellMat = layout.extractCellAsMat("D")
                if (cellMat != null) {
                    cellMat.useMat { mat ->
                        if (debug) cellDExtractor.debugOutputDir = testCase.cellsDir
                        val result = cellDExtractor.extractDetail(mat)
                        cellDExtractor.debugOutputDir = null
                        extracted = result.value
                        if (debug) println("  Cell D OCR: '${result.rawText}' -> ${result.value?.displayString() ?: "FAILED"}")
                    }
                }
                cellResults["D"] = CellResult.compare(extracted, annotations.expectedValues["D"])
            }
        }

        // Build summary
        val withExpectations = cellResults.values.count { it.expected != null }
        val correct = cellResults.values.count { it.match == true }
        val accuracy = if (withExpectations > 0) correct.toDouble() / withExpectations else null

        val summary = ResultSummary(
            cellsTotal = cellResults.size,
            cellsWithExpectations = withExpectations,
            cellsCorrect = correct,
            accuracy = accuracy
        )

        val result = ProcessingResult(
            source = testCase.id,
            timestamp = Instant.now().toString(),
            detection = detectionResult,
            cells = cellResults,
            summary = summary
        )

        // Save result
        testCase.outputDir.mkdirs()
        result.save(testCase.resultFile)
        println("  Saved: result.json")

        return result
    }

    /**
     * Process all test cases in a directory.
     */
    fun processAll(corpusDir: File, useExpectedCorners: Boolean = false): List<ProcessingResult> {
        val testCases = TestCase.findAll(corpusDir)
        println("Found ${testCases.size} test cases in ${corpusDir.absolutePath}")

        val results = testCases.map { testCase ->
            // Clear scale cache between test cases - each image may have different camera distance
            (detector as? MultiScaleMatcher)?.clearCache()
            process(testCase, useExpectedCorners)
        }

        // Print summary
        println("\n=== Summary ===")
        val successful = results.count { it.detection.success }
        println("Detection: $successful/${results.size} successful")

        val totalWithExpectations = results.sumOf { it.summary.cellsWithExpectations }
        val totalCorrect = results.sumOf { it.summary.cellsCorrect }
        if (totalWithExpectations > 0) {
            val overallAccuracy = totalCorrect.toDouble() / totalWithExpectations * 100
            println("OCR Accuracy: $totalCorrect/$totalWithExpectations (${String.format("%.1f", overallAccuracy)}%)")
        }

        // Corner accuracy
        val cornerErrors = results.mapNotNull { it.detection.cornerErrorPixels }
        if (cornerErrors.isNotEmpty()) {
            val avgError = cornerErrors.average()
            val maxError = cornerErrors.max()
            println("Corner Error: avg=${String.format("%.1f", avgError)}px, max=${String.format("%.1f", maxError)}px")
        }

        return results
    }

    private fun createDetectedScreenFromCorners(source: ImageSource, corners: ScreenCorners): DetectedScreen =
        DetectedScreen(
            source = source,
            screenQuad = corners.toQuadrilateral(),
            confidence = 1.0f,
            detectionMethod = "manual",
        )

    private fun calculateCornerError(detected: ScreenCorners, expected: ScreenCorners): Double {
        val pairs = listOf(
            detected.topLeft to expected.topLeft,
            detected.topRight to expected.topRight,
            detected.bottomRight to expected.bottomRight,
            detected.bottomLeft to expected.bottomLeft
        )
        return pairs.map { (d, e) ->
            val dx = d.x - e.x
            val dy = d.y - e.y
            sqrt(dx * dx + dy * dy)
        }.average()
    }

    private fun Quadrilateral.toScreenCorners(): ScreenCorners {
        return ScreenCorners(
            topLeft = JsonPoint(topLeft.x, topLeft.y),
            topRight = JsonPoint(topRight.x, topRight.y),
            bottomRight = JsonPoint(bottomRight.x, bottomRight.y),
            bottomLeft = JsonPoint(bottomLeft.x, bottomLeft.y)
        )
    }
}
