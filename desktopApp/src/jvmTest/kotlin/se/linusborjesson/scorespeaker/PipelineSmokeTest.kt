package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import se.linusborjesson.scorespeaker.ocr.CellDExtractor
import se.linusborjesson.scorespeaker.pipeline.ImageBridge
import se.linusborjesson.scorespeaker.pipeline.fromBufferedImage
import se.linusborjesson.scorespeaker.pipeline.useMat
import se.linusborjesson.scorespeaker.pipeline.DetectedScreen
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.Point2D
import se.linusborjesson.scorespeaker.pipeline.Quadrilateral
import se.linusborjesson.scorespeaker.processing.MultiScaleMatcher
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.testdata.ScreenCorners
import se.linusborjesson.scorespeaker.testdata.TestCase
import se.linusborjesson.scorespeaker.testdata.findCorpusDir
import se.linusborjesson.scorespeaker.testdata.findTestDataDir
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/**
 * End-to-end smoke test that exercises the full detection + OCR pipeline
 * against the annotated test data. Doubles as a baseline benchmark — per-stage
 * timings are printed so refactors can be compared apples-to-apples.
 *
 * Tolerances are deliberately loose; this is a regression net, not a quality
 * gate. Tighten as the pipeline improves.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineSmokeTest {

    private val testDataDir: File = findTestDataDir()
    private val corpusDir: File = findCorpusDir()
    private val templateFile: File = File(testDataDir, "masked-template.png")
    // Current floor is ~14 px max across the 5 test cases. Most of that is
    // annotation placement, not detection error: the refiner locks onto the
    // outer edge of the display's white frame line (which is what maps the
    // quad onto the template's own boundary), while the hand-clicked
    // annotations sit anywhere between the frame line's inner edge and its
    // outer blur glow — a ±10 px definitional spread on these photos. This
    // gate only catches gross drift, not fine-grained accuracy.
    private val maxCornerErrorPx = 16.0

    private lateinit var detector: MultiScaleMatcher
    private var cellDExtractor: CellDExtractor? = null

    @BeforeAll
    fun setup() {
        assumeTrue(testDataDir.exists()) { "test-data dir not found: $testDataDir" }
        assumeTrue(templateFile.exists()) { "template not found: $templateFile" }

        val templateImage = ImageIO.read(templateFile)
        detector = MultiScaleMatcher(
            scales = listOf(0.3, 0.5, 0.7, 1.0),
            maxFeatures = 500,
            minInliers = 8,
            maxInputWidth = 2500,
        )
        detector.loadTemplate(ImageBridge.toMat(templateImage))

        cellDExtractor = glyphCellDExtractor(testDataDir)
    }

    @Test
    fun `detector finds every annotated display within tolerance`() {
        val cases = annotatedCases()
        assumeTrue(cases.isNotEmpty()) { "no annotated cases" }

        val failures = mutableListOf<String>()
        val errors = mutableListOf<Double>()
        var totalDetect = 0L

        for (case in cases) {
            detector.clearCache()
            val annotations = case.loadAnnotations()
            val expected = annotations.screenCorners!!
            val sourceImage = ImageIO.read(case.sourceFile)
            val source = ImageSource.fromBufferedImage(sourceImage, sourcePath = case.sourceFile.absolutePath)

            val (detected, dt) = timed { detector.detect(source) }
            totalDetect += dt

            if (detected == null) {
                failures += "${case.displayName}: detection failed"
                continue
            }
            val err = cornerError(detected.screenQuad, expected)
            errors += err
            if (err > maxCornerErrorPx) {
                failures += "${case.displayName}: corner error ${"%.1f".format(err)} px > $maxCornerErrorPx"
            }
        }

        printSummary(
            label = "Detection",
            n = cases.size,
            totalNanos = totalDetect,
            extra = "avg err=${"%.1f".format(errors.average())} px, max err=${"%.1f".format(errors.max())} px",
        )

        assertTrue(failures.isEmpty(), "Detection failures:\n  ${failures.joinToString("\n  ")}")
    }

    @Test
    fun `Cell D OCR matches expected score`() {
        val extractor = cellDExtractor
        assumeTrue(extractor != null) { "shot-font alphabet missing" }

        val cases = annotatedCases().filter { case ->
            (case.loadAnnotations().expectedValues["D"] as? ScoreShotValue) != null
        }
        assumeTrue(cases.isNotEmpty()) { "no Cell D expectations" }

        val failures = mutableListOf<String>()
        var totalExtract = 0L

        for (case in cases) {
            val annotations = case.loadAnnotations()
            val sourceImage = ImageIO.read(case.sourceFile)
            ImageSource.fromBufferedImage(sourceImage, sourcePath = case.sourceFile.absolutePath).use { source ->
                val expected = annotations.expectedValues["D"] as ScoreShotValue
                val detected = detectedScreenFromAnnotations(source, annotations.screenCorners!!)
                detected.rectifyAtDetectedResolution().use { view ->
                    val cellMat = view.withSiusCells().extractCellAsMat("D")
                    assertNotNull(cellMat, "${case.displayName}: extractCellAsMat(D) returned null")
                    cellMat.useMat { mat ->
                        val (result, dt) = timed { extractor!!.extractDetail(mat) }
                        totalExtract += dt
                        val actual = result.value
                        if (actual == null) {
                            failures += "${case.displayName}: parsed null from OCR='${result.rawText}', expected ${expected.displayString()}"
                            return@useMat
                        }
                        if (!expected.matches(actual)) {
                            failures += "${case.displayName}: expected ${expected.displayString()}, got ${actual.displayString()} (OCR='${result.rawText}')"
                        }
                    }
                }
            }
        }

        printSummary("Cell D extract", cases.size, totalExtract)
        assertTrue(failures.isEmpty(), "Cell D failures:\n  ${failures.joinToString("\n  ")}")
    }

    @Test
    fun `per-stage timing report`() {
        val cases = annotatedCases()
        assumeTrue(cases.isNotEmpty()) { "no annotated cases" }

        var loadNanos = 0L
        var detectNanos = 0L
        var detectCachedNanos = 0L
        var rectifyNanos = 0L
        var extractNanos = 0L
        var ocrNanos = 0L

        // Pass 1: cold detection (cache cleared per case)
        for (case in cases) {
            detector.clearCache()
            val annotations = case.loadAnnotations()
            val expected = annotations.screenCorners ?: continue

            val (sourceImage, td0) = timed { ImageIO.read(case.sourceFile) }
            loadNanos += td0
            ImageSource.fromBufferedImage(sourceImage, sourcePath = case.sourceFile.absolutePath).use { source ->
                val (_, td1) = timed { detector.detect(source) }
                detectNanos += td1

                val detected = detectedScreenFromAnnotations(source, expected)
                detected.rectifyAtDetectedResolution().use { view ->
                    val (rectified, td2) = timed { view.rectifiedMat }
                    rectifyNanos += td2
                    check(rectified.cols() > 0)

                    val layout = view.withSiusCells()
                    val (cellMat, td3) = timed { layout.extractCellAsMat("D") }
                    extractNanos += td3

                    cellDExtractor?.let { extractor ->
                        val (_, td4) = timed { cellMat?.useMat { extractor.extractDetail(it) } }
                        ocrNanos += td4
                    }
                }
            }
        }

        // Pass 2: warm detection (cache preserved across the SAME case repeated). This
        // simulates continuous reading from a fixed camera position — once a scale
        // works, every subsequent frame hits the cached scale on the first try.
        val warmCase = cases.firstOrNull { it.loadAnnotations().screenCorners != null }
        if (warmCase != null) {
            val sourceImage = ImageIO.read(warmCase.sourceFile)
            ImageSource.fromBufferedImage(sourceImage, sourcePath = warmCase.sourceFile.absolutePath).use { source ->
                detector.clearCache()
                detector.detect(source) // warm the cache
                // Now time 5 warm matches against the same image
                val warmRuns = 5
                repeat(warmRuns) {
                    val (_, dt) = timed { detector.detect(source) }
                    detectCachedNanos += dt
                }
                detectCachedNanos /= warmRuns
            }
        }

        println()
        println("=== Per-stage timing (sum over ${cases.size} cases) ===")
        report("Load image (ImageIO.read)", loadNanos, cases.size)
        report("Detect (cold, cache cleared)", detectNanos, cases.size)
        report("Detect (warm, cached scale)", detectCachedNanos, n = 1)
        report("Rectify (warpPerspective)", rectifyNanos, cases.size)
        report("Extract cell D", extractNanos, cases.size)
        if (cellDExtractor != null) report("OCR cell D", ocrNanos, cases.size)
        println()
    }

    private fun annotatedCases(): List<TestCase> =
        TestCase.findAll(corpusDir).filter { it.loadAnnotations().screenCorners != null }

    private fun detectedScreenFromAnnotations(source: ImageSource, corners: ScreenCorners): DetectedScreen =
        DetectedScreen(source, corners.toQuadrilateral(), confidence = 1.0f, detectionMethod = "manual")

    private fun cornerError(quad: Quadrilateral, expected: ScreenCorners): Double {
        fun d(a: Point2D, b: Double, c: Double): Double {
            val dx = a.x - b
            val dy = a.y - c
            return sqrt(dx * dx + dy * dy)
        }
        return listOf(
            d(quad.topLeft, expected.topLeft.x, expected.topLeft.y),
            d(quad.topRight, expected.topRight.x, expected.topRight.y),
            d(quad.bottomRight, expected.bottomRight.x, expected.bottomRight.y),
            d(quad.bottomLeft, expected.bottomLeft.x, expected.bottomLeft.y),
        ).average()
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - t0)
    }

    private fun printSummary(label: String, n: Int, totalNanos: Long, extra: String? = null) {
        val avg = totalNanos.nanoseconds / n
        val total = totalNanos.nanoseconds
        val suffix = if (extra != null) " — $extra" else ""
        println("  [$label] n=$n, total=$total, avg/case=$avg$suffix")
    }

    private fun report(label: String, totalNanos: Long, n: Int) {
        val avg = totalNanos.nanoseconds / n
        val total = totalNanos.nanoseconds
        println("  %-32s total=%-10s avg=%s".format(label, total, avg))
    }
}

