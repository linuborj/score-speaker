package se.linusborjesson.scorespeaker.pipeline

import org.opencv.core.Mat

/**
 * Immutable reference to a source frame. Holds a single [Mat] which the
 * pipeline operates on directly — no lazy conversion, no AWT or Android-Bitmap
 * dependency in the type itself.
 *
 * The Mat is owned by this `ImageSource` and released on [close].
 *
 * Construction from platform-native image types happens through small factory
 * extensions in the respective platform source sets — e.g.
 * `ImageSource.fromBufferedImage(...)` in the desktop harness, or building one
 * directly from `org.opencv.android.Utils.bitmapToMat(...)` in Android code.
 */
class ImageSource(
    val mat: Mat,
    val timestamp: Long = System.currentTimeMillis(),
    val sourcePath: String? = null,
) : AutoCloseable {
    val width: Int get() = mat.cols()
    val height: Int get() = mat.rows()

    override fun close() {
        mat.release()
    }

    companion object
}
