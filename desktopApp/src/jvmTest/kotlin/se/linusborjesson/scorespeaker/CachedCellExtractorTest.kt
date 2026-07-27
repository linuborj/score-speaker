package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import se.linusborjesson.scorespeaker.ocr.CellDExtractor
import se.linusborjesson.scorespeaker.pipeline.fromBufferedImage
import se.linusborjesson.scorespeaker.pipeline.CachedCellExtractor
import se.linusborjesson.scorespeaker.pipeline.CellExtractor
import se.linusborjesson.scorespeaker.pipeline.DetectedScreen
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.useMat
import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.testdata.TestCase
import se.linusborjesson.scorespeaker.testdata.findCorpusDir
import se.linusborjesson.scorespeaker.testdata.findTestDataDir
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Verifies that the cell-level cache short-circuits OCR on identical
 * consecutive frames, and quantifies the win on real test data.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CachedCellExtractorTest {

    private val testDataDir = findTestDataDir()
    private val corpusDir = findCorpusDir()
    private val templateFile = File(testDataDir, "masked-template.png")

    private var cellD: CellDExtractor? = null

    @BeforeAll
    fun setup() {
        assumeTrue(testDataDir.exists())
        assumeTrue(templateFile.exists())
        cellD = glyphCellDExtractor(testDataDir)
    }

    @Test
    fun `identical consecutive frames hit the cache`() {
        val (case, _) = pickCellDCase() ?: return
        val rectified = rectifiedCellMat(case, "D")
        val extractor = countingExtractor()
        val cached = CachedCellExtractor(extractor)

        repeat(10) { rectified.clone().useMat(cached::extract) }
        rectified.release()

        assertEquals(1, extractor.calls, "extractor should have run exactly once across 10 identical frames")
        assertEquals(9, cached.hitCount, "9 of 10 ticks should be cache hits")
    }

    @Test
    fun `different content forces a re-extract`() {
        val (caseA, _) = pickCellDCase(skip = 0) ?: return
        val (caseB, _) = pickCellDCase(skip = 1) ?: return
        val matA = rectifiedCellMat(caseA, "D")
        val matB = rectifiedCellMat(caseB, "D")
        val extractor = countingExtractor()
        val cached = CachedCellExtractor(extractor)

        // Same → 1 miss
        matA.clone().useMat(cached::extract)
        // Same again → 1 hit
        matA.clone().useMat(cached::extract)
        // Different → 1 miss
        matB.clone().useMat(cached::extract)
        // Same as the new content → 1 hit
        matB.clone().useMat(cached::extract)

        matA.release(); matB.release()
        assertEquals(2, extractor.calls, "two distinct contents should trigger two extractor calls")
        assertEquals(2, cached.hitCount)
        assertEquals(2, cached.missCount)
    }

    @Test
    fun `steady-state per-frame timing with cache`() {
        val real = cellD ?: return
        val (case, _) = pickCellDCase() ?: return

        val cellMat = rectifiedCellMat(case, "D")
        val cached = CachedCellExtractor(real)

        // Warm-up: 3 calls fill the cache.
        repeat(3) { cellMat.clone().useMat(cached::extract) }
        cached.reset()

        val n = 50
        var hitNanos = 0L
        var firstMiss = -1L

        // Cache MISS once (force by resetting cache).
        cached.reset()
        val (firstVal, missDt) = timed { cellMat.clone().useMat(cached::extract) }
        firstMiss = missDt
        assertNotNull(firstVal)
        assertTrue(firstVal is ScoreShotValue)

        // Then n cache HITS.
        repeat(n) {
            val (_, dt) = timed { cellMat.clone().useMat(cached::extract) }
            hitNanos += dt
        }
        cellMat.release()

        val avgHit = (hitNanos / n).nanoseconds
        val miss = firstMiss.nanoseconds
        println()
        println("=== Cell D OCR cost ===")
        println("  Cache miss (full OCR): $miss")
        println("  Cache hit (avg of $n): $avgHit")
        println("  Speedup: ${"%.0f".format(miss.inWholeNanoseconds.toDouble() / (hitNanos / n))}×")
        println("  Cache: ${cached.statsLine()}")
        println()
    }

    private fun pickCellDCase(skip: Int = 0): Pair<TestCase, ScoreShotValue>? {
        val cases = TestCase.findAll(corpusDir)
            .mapNotNull { case ->
                val expected = case.loadAnnotations().expectedValues["D"] as? ScoreShotValue
                if (expected != null && case.loadAnnotations().screenCorners != null) case to expected else null
            }
        return cases.drop(skip).firstOrNull()
    }

    private fun rectifiedCellMat(case: TestCase, cellName: String): org.opencv.core.Mat {
        val sourceImage = ImageIO.read(case.sourceFile)
        val corners = case.loadAnnotations().screenCorners!!
        return ImageSource.fromBufferedImage(sourceImage, sourcePath = case.sourceFile.absolutePath).use { source ->
            val detected = DetectedScreen(source, corners.toQuadrilateral(), 1.0f, "manual")
            detected.rectifyAtDetectedResolution().use { view ->
                val layout = view.withSiusCells()
                layout.extractCellAsMat(cellName)!!
            }
        }
    }

    private fun countingExtractor() = object : CellExtractor {
        var calls = 0
        override fun extract(cellMat: org.opencv.core.Mat): CellValue? {
            calls++
            return null
        }
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - t0)
    }
}

