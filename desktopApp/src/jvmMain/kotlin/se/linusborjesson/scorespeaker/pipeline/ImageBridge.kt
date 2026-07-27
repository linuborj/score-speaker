package se.linusborjesson.scorespeaker.pipeline

import org.opencv.core.CvType
import org.opencv.core.Mat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte

/**
 * Fast, lossless conversion between [BufferedImage] and OpenCV [Mat].
 *
 * Direct byte copy in BGR order, matching the layout OpenCV expects — never
 * an encode/decode round-trip (PNG encoding at stage boundaries costs
 * hundreds of MB and seconds per 8K frame).
 */
object ImageBridge {

    /**
     * Convert a BufferedImage into a `CV_8UC3` Mat in BGR channel order.
     *
     * Idempotently loads the OpenCV native library on first use — the new
     * Mat constructor calls into native code, so a caller hitting [toMat]
     * before any other OpenCV-using code would otherwise crash with
     * `UnsatisfiedLinkError`.
     */
    fun toMat(image: BufferedImage): Mat {
        CoordinateTransform.ensureOpenCv()
        val w = image.width
        val h = image.height
        // Reuse the source buffer if it's already 3BYTE_BGR; otherwise repack into one.
        val bgr = if (image.type == BufferedImage.TYPE_3BYTE_BGR) {
            image
        } else {
            BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR).also { dst ->
                val g = dst.createGraphics()
                try {
                    g.drawImage(image, 0, 0, null)
                } finally {
                    g.dispose()
                }
            }
        }
        val mat = Mat(h, w, CvType.CV_8UC3)
        val bytes = (bgr.raster.dataBuffer as DataBufferByte).data
        mat.put(0, 0, bytes)
        return mat
    }

    /**
     * Convert a Mat to a BufferedImage. Supports 1-channel grayscale and 3-channel BGR.
     */
    fun toBufferedImage(mat: Mat): BufferedImage {
        val w = mat.cols()
        val h = mat.rows()
        return when (mat.channels()) {
            1 -> {
                val image = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
                val bytes = (image.raster.dataBuffer as DataBufferByte).data
                mat.get(0, 0, bytes)
                image
            }
            3 -> {
                val image = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
                val bytes = (image.raster.dataBuffer as DataBufferByte).data
                mat.get(0, 0, bytes)
                image
            }
            else -> error("Unsupported channel count: ${mat.channels()}")
        }
    }
}

