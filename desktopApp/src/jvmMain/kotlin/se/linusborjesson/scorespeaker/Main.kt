package se.linusborjesson.scorespeaker

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.testdata.TestCase
import se.linusborjesson.scorespeaker.testdata.TestDataProcessor
import se.linusborjesson.scorespeaker.testdata.findCorpusDir
import se.linusborjesson.scorespeaker.testdata.findTestDataDir
import java.io.File

fun main(args: Array<String>) {
    val argList = args.toList()

    when {
        argList.contains("--help") || argList.contains("-h") -> {
            printHelp()
            return
        }
        argList.contains("--test-opencv") -> {
            try {
                CoordinateTransform.ensureOpenCv()
                println("OpenCV loaded: ${org.opencv.core.Core.VERSION}")
                System.exit(0)
            } catch (e: Throwable) {
                println("OpenCV load failed: ${e.message}")
                System.exit(1)
            }
        }
        argList.contains("--process-all") -> {
            processAll(argList)
            return
        }
        argList.contains("--process") -> {
            processSingle(argList)
            return
        }
        argList.contains("--list") -> {
            listTestCases()
            return
        }
        argList.contains("--theme-gallery") -> {
            launchThemeGallery()
            return
        }
        argList.contains("--theme-render") -> {
            renderThemeGallery(getArgValue(argList, "--theme-render") ?: "/tmp/theme-gallery.png")
            return
        }
        argList.contains("--live-render") -> {
            val (w, h) = renderSize(argList)
            renderComposable(
                getArgValue(argList, "--live-render") ?: "/tmp/live-screen.png",
                width = w, height = h,
            ) { se.linusborjesson.scorespeaker.ui.LiveScreenPreview() }
            return
        }
        argList.contains("--history-render") -> {
            val (w, h) = renderSize(argList)
            renderComposable(getArgValue(argList, "--history-render") ?: "/tmp/history.png", w, h) {
                se.linusborjesson.scorespeaker.ui.HistoryScreenPreview()
            }
            return
        }
        argList.contains("--session-render") -> {
            val (w, h) = renderSize(argList)
            renderComposable(getArgValue(argList, "--session-render") ?: "/tmp/session.png", w, h) {
                se.linusborjesson.scorespeaker.ui.SessionDetailScreenPreview()
            }
            return
        }
        argList.contains("--settings-render") -> {
            val (w, h) = renderSize(argList)
            renderComposable(getArgValue(argList, "--settings-render") ?: "/tmp/settings.png", w, h) {
                se.linusborjesson.scorespeaker.ui.SettingsScreenPreview()
            }
            return
        }
        argList.contains("--template-matching") -> {
            launchTemplateMatchingApp()
            return
        }
        else -> {
            // Default: launch GUI
            launchGui()
        }
    }
}

private fun printHelp() {
    println("""
ScoreSpeaker Desktop Tools

Usage: ./gradlew :desktopApp:run --args="<command> [options]"

GUI Commands:
  (no args)              Launch labeling/annotation GUI
  --theme-gallery        Launch the UI theme & primitives gallery (design vibes)
  --template-matching    Launch template matching debug GUI

Headless Commands:
  --list                 List all test cases
  --process <name>       Process a single test case
  --process-all          Process all test cases

Options:
  --debug                Save debug images (rectified, cells)
  --use-expected         Use annotated corners instead of detecting
  --ocr                  Enable Cell D reading (glyph matcher vs annotations)
  --template <path>      Path to template image (default: test-data/masked-template.png)

Other:
  --test-opencv          Test OpenCV initialization
  --help, -h             Show this help

Examples:
  ./gradlew :desktopApp:run --args="--list"
  ./gradlew :desktopApp:run --args="--process capture_20251221_152548 --debug"
  ./gradlew :desktopApp:run --args="--process-all --use-expected"
    """.trimIndent())
}

private fun listTestCases() {
    val corpusDir = findCorpusDir()
    val testCases = TestCase.findAll(corpusDir)

    println("Test cases in ${corpusDir.absolutePath}:\n")

    if (testCases.isEmpty()) {
        println("  (none)")
        return
    }

    for (case in testCases) {
        val annotations = case.loadAnnotations()
        val hasCorners = annotations.screenCorners != null
        val expectedCount = annotations.expectedValues.size
        val hasResult = case.resultFile.exists()

        val status = buildString {
            if (hasCorners) append("corners") else append("no-corners")
            if (expectedCount > 0) append(", $expectedCount expected")
            if (hasResult) append(", has-result")
        }

        println("  ${case.displayName}  (${case.id})  [$status]")
    }

    println("\nTotal: ${testCases.size} test cases")
}

private fun processSingle(args: List<String>) {
    val processIndex = args.indexOf("--process")
    if (processIndex == -1 || processIndex >= args.size - 1) {
        println("Error: --process requires a test case name")
        println("Usage: --process <name>")
        System.exit(1)
        return
    }

    val caseName = args[processIndex + 1]
    val debug = args.contains("--debug")
    val useExpected = args.contains("--use-expected")
    val enableOcr = args.contains("--ocr")
    val templatePath = getArgValue(args, "--template")

    val templateFile = templatePath?.let { File(it) } ?: File(findTestDataDir(), "masked-template.png")

    if (!templateFile.exists()) {
        println("Error: Template not found: ${templateFile.absolutePath}")
        System.exit(1)
        return
    }

    val caseDir = File(findCorpusDir(), caseName)
    if (!caseDir.exists() || !File(caseDir, "source.png").exists()) {
        println("Error: Test case not found: $caseName")
        println("Available test cases:")
        listTestCases()
        System.exit(1)
        return
    }

    val testCase = TestCase(caseDir)
    val processor = TestDataProcessor(templateFile, debug = debug, enableOcr = enableOcr)
    val result = processor.process(testCase, useExpectedCorners = useExpected)

    // Output result JSON to stdout for easy parsing
    println("\n--- Result JSON ---")
    println(se.linusborjesson.scorespeaker.testdata.testDataJson.encodeToString(
        se.linusborjesson.scorespeaker.testdata.ProcessingResult.serializer(),
        result
    ))
}

private fun processAll(args: List<String>) {
    val debug = args.contains("--debug")
    val useExpected = args.contains("--use-expected")
    val enableOcr = args.contains("--ocr")
    val templatePath = getArgValue(args, "--template")

    val templateFile = templatePath?.let { File(it) } ?: File(findTestDataDir(), "masked-template.png")

    if (!templateFile.exists()) {
        println("Error: Template not found: ${templateFile.absolutePath}")
        System.exit(1)
        return
    }

    val processor = TestDataProcessor(templateFile, debug = debug, enableOcr = enableOcr)
    processor.processAll(findCorpusDir(), useExpectedCorners = useExpected)
}

/** Optional `--size WxH` for the screen-render commands (default landscape phone-ish). */
private fun renderSize(args: List<String>): Pair<Int, Int> {
    val spec = getArgValue(args, "--size") ?: return 916 to 412
    val (w, h) = spec.split("x", limit = 2).map { it.toInt() }
    return w to h
}

private fun getArgValue(args: List<String>, name: String): String? {
    val index = args.indexOf(name)
    return if (index != -1 && index < args.size - 1) args[index + 1] else null
}

/** Window/taskbar icon — rendered from art/score-speaker-icon.svg. */
private fun appWindowIcon() =
    BitmapPainter(useResource("icons/scorespeaker.png", ::loadImageBitmap))

private fun launchGui() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            icon = appWindowIcon(),
            title = "ScoreSpeaker - Test Data Tool",
            state = rememberWindowState(width = 1400.dp, height = 900.dp)
        ) {
            LabelingApp()
        }
    }
}

/** Offscreen-render the gallery to a PNG (no window) — for sharing a static vibe shot. */
private fun renderThemeGallery(path: String) =
    renderComposable(path, width = 980, height = 1640) {
        se.linusborjesson.scorespeaker.ui.ThemeGallery()
    }

/** Render any composable to a PNG offscreen (no window). */
private fun renderComposable(path: String, width: Int, height: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
    val scene = androidx.compose.ui.ImageComposeScene(
        width = width, height = height,
        density = androidx.compose.ui.unit.Density(1f),
        content = content,
    )
    try {
        val image = scene.render()
        val data = image.encodeToData() ?: error("PNG encode failed")
        File(path).writeBytes(data.bytes)
        println("Wrote $path (${width}x$height)")
    } finally {
        scene.close()
    }
}

private fun launchThemeGallery() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            icon = appWindowIcon(),
            title = "ScoreSpeaker - Theme Gallery",
            state = rememberWindowState(width = 960.dp, height = 720.dp)
        ) {
            se.linusborjesson.scorespeaker.ui.ThemeGallery()
        }
    }
}

private fun launchTemplateMatchingApp() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            icon = appWindowIcon(),
            title = "ScoreSpeaker - Template Matching",
            state = rememberWindowState(width = 1400.dp, height = 900.dp)
        ) {
            TemplateMatchingApp()
        }
    }
}


