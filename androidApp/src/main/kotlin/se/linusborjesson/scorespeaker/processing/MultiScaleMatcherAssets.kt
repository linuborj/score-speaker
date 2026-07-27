package se.linusborjesson.scorespeaker.processing

import android.content.Context
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import se.linusborjesson.scorespeaker.Log

/**
 * Loads a SIUS-display template image from app assets and registers it with
 * [matcher]. Mirrors the desktop `loadTemplate` flow (which reads from File)
 * but reads from the APK asset bundle so no filesystem path is needed.
 *
 * Expects the PNG at `assets/<assetPath>` — defaults to `templates/sius-display.png`.
 *
 * Returns true on success, false if the asset is missing or undecodable.
 */
fun MultiScaleMatcher.loadTemplateFromAssets(
    context: Context,
    assetPath: String = "templates/sius-display.png",
    tolerance: Int = 30,
): Boolean {
    val bytes = try {
        context.assets.open(assetPath).use { it.readBytes() }
    } catch (e: Exception) {
        Log.warn { "MultiScaleMatcher: template asset $assetPath not found (${e.message})" }
        return false
    }
    val mat = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_UNCHANGED)
    if (mat.empty()) {
        Log.warn { "MultiScaleMatcher: could not decode template asset $assetPath" }
        return false
    }
    return try {
        loadTemplateWithColorKey(mat, tolerance = tolerance)
        true
    } finally {
        mat.release()
    }
}
