package se.linusborjesson.scorespeaker.pipeline

import java.awt.image.BufferedImage

/** Desktop convenience: convert a [BufferedImage] to an owned-Mat [ImageSource]. */
fun ImageSource.Companion.fromBufferedImage(
    image: BufferedImage,
    timestamp: Long = System.currentTimeMillis(),
    sourcePath: String? = null,
): ImageSource = ImageSource(ImageBridge.toMat(image), timestamp, sourcePath)
