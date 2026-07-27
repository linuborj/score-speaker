package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.processing.geometricTargetCenter
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeometricTargetCenterTest {

    @BeforeAll
    fun setup() {
        CoordinateTransform.ensureOpenCv()
    }

    @Test
    fun `returns geometric centre of a rectangular Mat`() {
        val mat = Mat(600, 800, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        try {
            val centre = geometricTargetCenter(mat)
            assertEquals(400.0, centre!!.x)
            assertEquals(300.0, centre.y)
        } finally {
            mat.release()
        }
    }

    @Test
    fun `returns null on empty Mat`() {
        assertNull(geometricTargetCenter(Mat()))
    }
}
