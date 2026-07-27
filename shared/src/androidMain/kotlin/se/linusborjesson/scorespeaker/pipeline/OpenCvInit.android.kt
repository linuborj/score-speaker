package se.linusborjesson.scorespeaker.pipeline

import org.opencv.android.OpenCVLoader

internal actual fun loadOpenCvNative() {
    if (!OpenCVLoader.initLocal()) {
        error("OpenCV native library failed to load")
    }
}
