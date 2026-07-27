package se.linusborjesson.scorespeaker.pipeline

internal actual fun loadOpenCvNative() {
    nu.pattern.OpenCV.loadLocally()
}
