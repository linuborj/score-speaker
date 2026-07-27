package se.linusborjesson.scorespeaker.pipeline

/**
 * Platform-specific OpenCV native-library loader.
 *
 * Both platforms expose the same `org.opencv.*` Java API, but they load the
 * native code differently:
 *  - Desktop: `nu.pattern.OpenCV.loadLocally()` extracts a bundled .so/.dll
 *    and `System.load`s it (from the openpnp jar).
 *  - Android: `org.opencv.android.OpenCVLoader.initLocal()` does the
 *    equivalent for the .so shipped in the official OpenCV Android AAR.
 *
 * Called once, idempotently, from [CoordinateTransform.ensureOpenCv].
 */
internal expect fun loadOpenCvNative()
