package se.linusborjesson.scorespeaker.pipeline

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 64-bit *dHash* (difference hash): downsample to 9×8 gray, then each
 * output bit is "left pixel < right pixel". The downsample averages out
 * camera sensor noise; the difference operation is robust to global
 * brightness shifts. Two visually-distinct content variants have Hamming
 * distance > 10 in practice (digits like "7.6" vs "8.6" flip ~25 bits), so
 * tolerances of 5–8 are safe against noise without false hits on content.
 */
object ImageHash {

    fun dHash(mat: Mat): Long {
        val gray = if (mat.channels() > 1) {
            Mat().also { Imgproc.cvtColor(mat, it, Imgproc.COLOR_BGR2GRAY) }
        } else {
            mat
        }
        val small = Mat()
        try {
            Imgproc.resize(gray, small, Size(9.0, 8.0), 0.0, 0.0, Imgproc.INTER_AREA)
            // Pull all 72 bytes in one JNI hop, not 72 individual .get() calls.
            val buf = ByteArray(72)
            small.get(0, 0, buf)
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                val rowOffset = y * 9
                for (x in 0 until 8) {
                    val left = buf[rowOffset + x].toInt() and 0xFF
                    val right = buf[rowOffset + x + 1].toInt() and 0xFF
                    if (left < right) hash = hash or (1L shl bit)
                    bit++
                }
            }
            return hash
        } finally {
            small.release()
            if (gray !== mat) gray.release()
        }
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
