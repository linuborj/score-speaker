package se.linusborjesson.scorespeaker

import android.content.Context
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import se.linusborjesson.scorespeaker.pipeline.Quadrilateral
import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Inputs to a single capture write. Optional fields are skipped from the
 * output when null — e.g. detection failure produces a capture with just
 * `source.png` + `metadata.json` + a minimal `capture-debug.json`.
 */
data class CaptureInputs(
    val fullFrameBgr: Mat,
    val rectifiedMat: Mat? = null,
    val screenQuad: Quadrilateral? = null,
    val dCellMat: Mat? = null,
    val dValue: CellValue? = null,
    val detectionMethod: String? = null,
    val detectionConfidence: Float? = null,
)

/**
 * Writes a captured frame as one zip per capture, grouped in a folder per
 * date. The zip's contents are the exact directory shape the desktop test
 * harness expects, so an unzipped capture can be dropped straight into
 * `desktopApp/test-data/<dir>/` and picked up by `TestCase.findAll(...)`
 * once `annotations.json` is added (via the desktop labeling GUI).
 *
 *   <files>/captures/<yyyy-MM-dd>/<HHmmss-SSS-shotN|noread-rand>.zip
 *     ├── source.png          full BGR frame (test-case source)
 *     ├── metadata.json       matches TestCaseMetadata schema
 *     ├── capture-debug.json  detection result + parsed values
 *     ├── rectified.png       rectified display (if detected)
 *     └── crops/D.png         rectified D cell (if detected)
 *
 * Pull from device and import:
 *   adb pull /sdcard/Android/data/se.linusborjesson.scorespeaker/files/captures /tmp
 *   unzip /tmp/captures/<date>/<name>.zip -d desktopApp/test-data/<name>
 */
fun writeCapture(context: Context, inputs: CaptureInputs): File {
    val now = LocalDateTime.now()
    val dayDir = File(
        File(context.getExternalFilesDir(null), "captures"),
        now.format(DateTimeFormatter.ISO_LOCAL_DATE),
    ).apply { mkdirs() }
    val zipFile = File(dayDir, "${captureName(inputs, now)}.zip")

    ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
        // Entries are PNGs (already compressed) — skip a pointless second
        // deflate pass; this runs on the camera analyzer thread.
        zip.setLevel(Deflater.NO_COMPRESSION)

        zip.putPng("source.png", inputs.fullFrameBgr)
        inputs.rectifiedMat?.let { zip.putPng("rectified.png", it) }
        inputs.dCellMat?.let { zip.putPng("crops/D.png", it) }

        val timestamp = Instant.now().toString()
        zip.putText(
            "metadata.json",
            """
            |{
            |    "originalFileName": "android-camerax-$timestamp.png",
            |    "importedAt": "$timestamp",
            |    "description": "Captured live on Android via MainActivity"
            |}
            """.trimMargin(),
        )
        zip.putText("capture-debug.json", buildDebugJson(inputs))
    }

    return zipFile
}

/**
 * Readable, sortable capture name: local time plus the OCR'd shot number when
 * cell D parsed (e.g. `101503-217-shot6`, else `...-noread`), so a pulled
 * session can be skimmed without opening each capture-debug.json. A short
 * random suffix guards against clock collisions. The date lives in the
 * parent folder name.
 */
private fun captureName(inputs: CaptureInputs, now: LocalDateTime): String {
    val time = now.format(DateTimeFormatter.ofPattern("HHmmss-SSS"))
    val shot = (inputs.dValue as? ScoreShotValue)?.shot
    val label = if (shot != null) "shot$shot" else "noread"
    return "$time-$label-${UUID.randomUUID().toString().take(4)}"
}

private fun ZipOutputStream.putPng(name: String, mat: Mat) {
    val buf = MatOfByte()
    try {
        if (!Imgcodecs.imencode(".png", mat, buf)) {
            Log.warn { "Capture: PNG encode failed for $name" }
            return
        }
        putNextEntry(ZipEntry(name))
        write(buf.toArray())
        closeEntry()
    } finally {
        buf.release()
    }
}

private fun ZipOutputStream.putText(name: String, text: String) {
    putNextEntry(ZipEntry(name))
    write(text.toByteArray())
    closeEntry()
}

private fun buildDebugJson(inputs: CaptureInputs): String {
    val frameLine = """    "frame": { "width": ${inputs.fullFrameBgr.cols()}, "height": ${inputs.fullFrameBgr.rows()} }"""
    val detectionLine = if (inputs.screenQuad != null) {
        val q = inputs.screenQuad
        // Locale.US: default-locale format on a Swedish device emits "1,0000",
        // which is invalid JSON (found in a real field capture).
        val confidence = inputs.detectionConfidence?.let { String.format(java.util.Locale.US, "%.4f", it) } ?: "null"
        val method = inputs.detectionMethod?.toJsonString() ?: "null"
        """    "detection": {
        |        "method": $method,
        |        "confidence": $confidence,
        |        "screenCornersDetected": {
        |            "topLeft":     { "x": ${q.topLeft.x},     "y": ${q.topLeft.y} },
        |            "topRight":    { "x": ${q.topRight.x},    "y": ${q.topRight.y} },
        |            "bottomRight": { "x": ${q.bottomRight.x}, "y": ${q.bottomRight.y} },
        |            "bottomLeft":  { "x": ${q.bottomLeft.x},  "y": ${q.bottomLeft.y} }
        |        }
        |    }""".trimMargin()
    } else {
        """    "detection": null"""
    }
    val parsedDLine = """    "parsedD": ${inputs.dValue?.displayString()?.toJsonString() ?: "null"}"""

    return "{\n$frameLine,\n$detectionLine,\n$parsedDLine\n}\n"
}

private fun String.toJsonString(): String = buildString {
    append('"')
    for (c in this@toJsonString) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}
