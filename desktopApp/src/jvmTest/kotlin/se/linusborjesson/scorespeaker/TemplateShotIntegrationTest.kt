package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.imgcodecs.Imgcodecs
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.pipeline.DetectedScreen
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.fromBufferedImage
import se.linusborjesson.scorespeaker.testdata.TestCase
import se.linusborjesson.scorespeaker.testdata.findCorpusDir
import se.linusborjesson.scorespeaker.testdata.findTestDataDir
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

/**
 * Pins the production Cell D reader — CellDExtractor with the real-glyph
 * alphabet — against the annotated corpus and a degraded field capture
 * (truth "8") that defeats less constrained readers. This is exactly the
 * configuration the Android app runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateShotIntegrationTest {

    private val testDataDir = findTestDataDir()
    private val corpusDir = findCorpusDir()

    @Test
    fun `extractor with glyph matcher reads the full corpus`() {
        CoordinateTransform.ensureOpenCv()
        val extractor = glyphCellDExtractor(testDataDir)
        assumeTrue(extractor != null, "glyph alphabet missing — run GlyphAlphabetTools.harvest")

        for (case in TestCase.findAll(corpusDir)) {
            val expected = case.loadAnnotations().expectedValues["D"] as? ScoreShotValue ?: continue
            val corners = case.loadAnnotations().screenCorners ?: continue
            val source = ImageIO.read(case.sourceFile)
            val value = ImageSource.fromBufferedImage(source, sourcePath = case.sourceFile.absolutePath).use { src ->
                DetectedScreen(src, corners.toQuadrilateral(), 1.0f, "manual")
                    .rectifyAtDetectedResolution().use { view ->
                        val dMat = view.withSiusCells().extractCellAsMat("D")!!
                        try {
                            extractor!!.extract(dMat)
                        } finally {
                            dMat.release()
                        }
                    }
            }
            val got = value as? ScoreShotValue
            assertEquals(expected.shot, got?.shot, "${case.directory.name.take(8)}: shot")
            assertEquals(expected.mode, got?.mode, "${case.directory.name.take(8)}: mode")
        }

        // The field misread: full extractor on the phone's own crop.
        val fieldCrop = File(corpusDir, "field-20260723-shot8-misread/crops/D.png")
        assumeTrue(fieldCrop.exists())
        val cell = Imgcodecs.imread(fieldCrop.absolutePath)
        val got = try {
            extractor!!.extract(cell) as? ScoreShotValue
        } finally {
            cell.release()
        }
        assertEquals("8", got?.shot, "degraded field capture: shot")
    }
}
