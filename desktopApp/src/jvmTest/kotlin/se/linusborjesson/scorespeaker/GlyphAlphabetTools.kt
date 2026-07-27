package se.linusborjesson.scorespeaker

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import se.linusborjesson.scorespeaker.cells.ScoreShotValue
import se.linusborjesson.scorespeaker.ocr.CellDExtractor
import se.linusborjesson.scorespeaker.ocr.DigitTemplateMatcher
import se.linusborjesson.scorespeaker.pipeline.CoordinateTransform
import se.linusborjesson.scorespeaker.pipeline.DetectedScreen
import se.linusborjesson.scorespeaker.pipeline.ImageBridge
import se.linusborjesson.scorespeaker.pipeline.ImageSource
import se.linusborjesson.scorespeaker.pipeline.ScreenDetector
import se.linusborjesson.scorespeaker.processing.MultiScaleMatcher
import se.linusborjesson.scorespeaker.pipeline.fromBufferedImage
import se.linusborjesson.scorespeaker.testdata.TestCase
import se.linusborjesson.scorespeaker.testdata.findCorpusDir
import se.linusborjesson.scorespeaker.testdata.findTestDataDir
import java.io.File
import javax.imageio.ImageIO

/**
 * Real-glyph alphabet tools for the shot-font (small white SIUS face).
 * Glyphs are stored binarized (white on black) under
 * `test-data/glyphs/shot-font/<char>/`, exactly the distribution
 * `DigitTemplateMatcher` compares against.
 *
 * **[harvestCellD] builds the shipped alphabet** — from Cell D's shot
 * region, the region the production reader reads, labeled by each case's
 * annotated shot number and mode.
 *
 * The two list-panel harvests are the superseded stand-in, kept because
 * the trick earns its place again whenever a display's Cell D coverage is
 * too thin to cover the alphabet (see docs/EXPERIMENTS.md):
 * [harvest] takes the rows of one hand-labeled case, and
 * [harvestSessionLists] generalises it — within a session every capture's
 * list shows the same history, so one case's annotated (shot, score)
 * labels the rows of every other. They yield an order of magnitude more
 * instances, of the wrong render.
 *
 * [evaluate] reads the alphabet back over every annotated case plus the
 * degraded `field-…-misread` capture (truth "8"), which is never
 * harvested. [evaluateHeldOut] is the number that means anything now that
 * train and test share a region: it rebuilds the alphabet per case with
 * that case's own donations excluded.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GlyphAlphabetTools {

    private val testDataDir = findTestDataDir()
    private val corpusDir = findCorpusDir()
    private val glyphDir = File(testDataDir, "glyphs/shot-font")

    /**
     * Gate for the tools that *write* the alphabet.
     *
     * These are tools wearing a test's clothes — they rewrite committed
     * test data. Left ungated, a plain `:desktopApp:jvmTest` silently
     * rebuilds the shipped alphabet, and since the superseded list-panel
     * harvests also run, it repopulates it with wrong-region glyphs. Set
     * `SCORE_SPEAKER_HARVEST=1` to run them deliberately.
     */
    private fun requireHarvestOptIn() = assumeTrue(
        System.getenv("SCORE_SPEAKER_HARVEST") != null,
        "alphabet-writing tool — set SCORE_SPEAKER_HARVEST=1 to run",
    )

    /**
     * Labeling aid: stack every case's Cell D shot region into one tall
     * PNG, in a stable order, so a human can read all the ground-truth
     * shot indices in a single glance instead of opening 20 crops. Writes
     * to `$SCORE_SPEAKER_MONTAGE` (default `/tmp/shot-crops.png`); prints
     * the row order so the image can be mapped back to case directories.
     */
    @Test
    fun montageShotCrops() {
        CoordinateTransform.ensureOpenCv()
        val cases = TestCase.findAll(corpusDir).sortedBy { it.directory.name }
        val rows = mutableListOf<Pair<String, Mat>>()
        for (case in cases) {
            val crop = File(case.directory, "output/cells/D_shot.png")
            if (!crop.exists()) continue
            val mat = Imgcodecs.imread(crop.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            if (mat.empty()) continue
            rows += case.directory.name.take(8) to mat
        }
        assumeTrue(rows.isNotEmpty(), "no D_shot crops — run --process-all --debug first")

        val rowH = 80
        val width = rows.maxOf { (it.second.cols() * rowH.toDouble() / it.second.rows()).toInt() }
        val sheet = Mat.zeros(rowH * rows.size, width, org.opencv.core.CvType.CV_8UC1)
        for ((i, row) in rows.withIndex()) {
            val scaled = Mat()
            val w = (row.second.cols() * rowH.toDouble() / row.second.rows()).toInt().coerceIn(1, width)
            Imgproc.resize(row.second, scaled, Size(w.toDouble(), rowH.toDouble()))
            scaled.copyTo(Mat(sheet, Rect(0, i * rowH, w, rowH)))
            scaled.release()
            row.second.release()
            println("row ${i + 1}: ${row.first}")
        }
        val out = System.getenv("SCORE_SPEAKER_MONTAGE") ?: "/tmp/shot-crops.png"
        Imgcodecs.imwrite(out, sheet)
        sheet.release()
        println("montage: $out (${rows.size} rows)")
    }

    /** Known shot rows of the harvest case's list panel, top to bottom. */
    private val listRows = listOf(
        "10" to "72",
        "11" to "68", "12" to "71", "13" to "69", "14" to "63", "15" to "69",
        "16" to "70", "17" to "74", "18" to "78", "19" to "75", "20" to "76",
    )

    /**
     * Corpus captures grouped by shooting session, by directory prefix.
     *
     * Within a session every capture's list panel shows the same shot
     * history, so the annotated `(shot, score)` of *any* case in the group
     * labels the corresponding row of *every* case's panel. The grouping
     * has to be explicit: shot numbers repeat across sessions with
     * different scores (shot 6 is 5.7 in one session and 8.1 in another),
     * so merging them would mislabel rows wholesale.
     *
     * Listed here: the 2026-08-01 range session, IMG_1926–1940.
     */
    private val sessions = listOf(
        setOf(
            "0acdcc8e", "2fa130da", "4834498d", "c283ad05", "0fb689c0", "622d652c",
            "f71d4b7c", "a09b2383", "639a57ed", "28564e94", "69bd6802", "4244eaf8",
            "0be5f71d", "2a25c301",
        ),
    )

    /**
     * Harvest the shot-font from the *list panel* (cell C) of every case in
     * every [sessions] group.
     *
     * Row labels are derived, never guessed: the bottom row is the case's
     * annotated shot, rows above it count down one shot at a time, and a
     * "Delsumma" subtotal row sits above every shot ≡ 1 (mod 10). A row is
     * harvested only when its component count is exactly
     * `shotDigits + 1 arrow + scoreDigits` — any other count means the
     * segmentation disagrees with the derivation, and a row we can't
     * account for is skipped rather than labelled on a hunch.
     */
    @Test
    fun harvestSessionLists() {
        requireHarvestOptIn()
        CoordinateTransform.ensureOpenCv()
        val cases = TestCase.findAll(corpusDir).associateBy { it.directory.name.take(8) }
        val saved = sortedMapOf<Char, Int>()
        var rowsUsed = 0
        var rowsSkipped = 0

        for (group in sessions) {
            // Scores for the session, pooled from every annotated case in it.
            val scores = mutableMapOf<Int, String>()
            for (prefix in group) {
                val shot = cases[prefix]?.annotatedShot() ?: continue
                val score = cases[prefix]?.annotatedScore() ?: continue
                scores[shot] = score.replace(".", "").replace(",", "")
            }
            if (scores.isEmpty()) continue

            for (prefix in group.sorted()) {
                val case = cases[prefix] ?: continue
                val anchor = case.annotatedShot() ?: continue
                val cellC = cellMatFor(case, "C")
                try {
                    val components = binarizedComponents(cellC)
                    val rows = clusterRows(components)
                    val labels = labelRows(anchor, rows.size)
                    for ((row, shot) in rows.zip(labels)) {
                        val maxH = row.maxOf { it.second.height }
                        val glyphs = row.filter { it.second.height >= maxH * 0.5 }.sortedBy { it.second.x }
                        val score = shot?.let { scores[it] }
                        if (shot == null || score == null) { rowsSkipped++; continue }
                        val shotDigits = shot.toString()
                        // shot digits + one arrow + score digits.
                        if (glyphs.size != shotDigits.length + 1 + score.length) { rowsSkipped++; continue }
                        rowsUsed++
                        val chars = "$shotDigits?$score" // '?' = the arrow, skipped
                        for ((i, g) in glyphs.withIndex()) {
                            val ch = chars[i]
                            if (ch == '?') continue
                            save(ch, g.first, "$prefix-row$shot-$i")
                            saved.merge(ch, 1, Int::plus)
                        }
                    }
                    components.forEach { it.first.release() }
                } finally {
                    cellC.release()
                }
            }
        }
        println("session harvest: $saved")
        println("rows used=$rowsUsed skipped=$rowsSkipped, total ${saved.values.sum()} glyphs")
        assumeTrue(rowsUsed > 0, "no session rows harvested")
    }

    /** The annotated Cell D shot number, or null when unannotated. */
    private fun TestCase.annotatedShot(): Int? =
        (loadAnnotations().expectedValues["D"] as? ScoreShotValue)?.shot?.toIntOrNull()

    /** The annotated Cell D score, or null when unannotated. */
    private fun TestCase.annotatedScore(): String? =
        (loadAnnotations().expectedValues["D"] as? ScoreShotValue)?.score

    /**
     * Shot number per list row, bottom-up from [anchor], for a panel
     * showing [rowCount] rows. `null` marks a row that carries no shot: a
     * "Delsumma" subtotal (the SIUS draws one after every tenth shot), or
     * the "Match" header that sits above the list until enough shots push
     * it off. Counting from the *bottom* is what makes this safe — the
     * anchor row is the one row whose identity is annotated, so anything
     * left over at the top is padded null and skipped rather than guessed.
     */
    private fun labelRows(anchor: Int, rowCount: Int): List<Int?> {
        val out = ArrayDeque<Int?>()
        var shot = anchor
        while (out.size < rowCount) {
            if (shot < 1) {
                out.addFirst(null)
                continue
            }
            out.addFirst(shot)
            if (out.size == rowCount) break
            if (shot > 1 && (shot - 1) % 10 == 0) {
                out.addFirst(null)
                if (out.size == rowCount) break
            }
            shot--
        }
        return out.toList()
    }

    /** Group components into panel rows by y-centre; a row gap is > half a glyph height. */
    private fun clusterRows(components: List<Pair<Mat, Rect>>): List<List<Pair<Mat, Rect>>> {
        val rows = mutableListOf<MutableList<Pair<Mat, Rect>>>()
        for (comp in components.sortedBy { it.second.y + it.second.height / 2 }) {
            val cy = comp.second.y + comp.second.height / 2
            val row = rows.lastOrNull()?.takeIf { r ->
                val ry = r.first().second.let { it.y + it.height / 2 }
                kotlin.math.abs(cy - ry) < comp.second.height
            }
            if (row != null) row += comp else rows += mutableListOf(comp)
        }
        return rows
    }

    /**
     * Harvest the shot-font from **Cell D's shot region** — the region the
     * production reader actually reads.
     *
     * This is the alphabet that should be shipped. The list-panel harvests
     * ([harvest], [harvestSessionLists]) exist because the corpus used to
     * hold only a handful of Cell D examples, which between them didn't
     * cover the ten digits — so the list panel stood in for the characters
     * that were missing. It is a different render: smaller, and laid out
     * against different neighbours. Templates from it are a distribution
     * mismatch, and more instances of the wrong render do not beat fewer
     * instances of the right one.
     *
     * With the 2026-08-01 session the corpus covers 0–9 and P in Cell D
     * itself, so the stand-in is no longer needed.
     *
     * Labels come from the annotated shot number and mode; a case is
     * harvested only when the segmented component count matches that
     * label exactly. Since train and test are now the same region,
     * [evaluateHeldOut] — which excludes each case's own donations — is
     * the only measurement that means anything.
     */
    @Test
    fun harvestCellD() {
        requireHarvestOptIn()
        CoordinateTransform.ensureOpenCv()
        val saved = sortedMapOf<Char, Int>()
        var used = 0
        var skipped = 0
        for (case in TestCase.findAll(corpusDir).sortedBy { it.directory.name }) {
            // Benchmarks never donate the templates that read them.
            if (case.directory.name.contains("misread")) continue
            val shot = case.loadAnnotations().expectedValues["D"] as? ScoreShotValue ?: continue
            val label = "${shot.shot}${shot.mode ?: ""}"
            val prefix = case.directory.name.take(8)
            val region = shotRegionMat(cellMatFor(case, "D"))
            try {
                val comps = binarizedComponents(region)
                    // The same aspect gate DigitTemplateMatcher applies when
                    // reading: the crop's left border bleeds in as a 1–3 px
                    // sliver the height filter alone keeps, and one stray
                    // component is enough to fail the count check.
                    .filter { (_, r) -> r.width <= r.height * 2.5 && r.height <= r.width * 8 }
                val maxH = comps.maxOfOrNull { it.second.height }
                val glyphs = if (maxH == null) emptyList()
                else comps.filter { it.second.height >= maxH * 0.5 }.sortedBy { it.second.x }
                if (glyphs.size == label.length) {
                    used++
                    for ((i, g) in glyphs.withIndex()) {
                        save(label[i], g.first, "$prefix-D-$i")
                        saved.merge(label[i], 1, Int::plus)
                    }
                } else {
                    skipped++
                    val shapes = glyphs.joinToString(" ") { "${it.second.width}x${it.second.height}@${it.second.x},${it.second.y}" }
                    println("  $prefix: ${glyphs.size} glyphs vs label '$label' — skipped [$shapes] cell=${region.cols()}x${region.rows()}")
                }
                comps.forEach { it.first.release() }
            } finally {
                region.release()
            }
        }
        println("cell-D harvest: $saved")
        println("cases used=$used skipped=$skipped, total ${saved.values.sum()} glyphs")
        assumeTrue(used > 0, "no cell-D glyphs harvested")
    }

    /**
     * Drop near-duplicate instances from the alphabet.
     *
     * A session donates many glyphs rendered at the same size under the
     * same exposure, so a large share of the harvest is redundant: the
     * matcher takes the best score over every instance, and an instance
     * that scores ~1.0 against one already kept can never change an
     * answer. It can only cost time — every instance is matched on every
     * cache miss, which is the one place the reader is not free.
     *
     * Greedy: keep an instance only if it differs from everything kept so
     * far for that character. Run after the harvests; re-run
     * [evaluateHeldOut] afterwards to confirm nothing was lost.
     */
    @Test
    fun pruneAlphabet() {
        requireHarvestOptIn()
        CoordinateTransform.ensureOpenCv()
        assumeTrue(glyphDir.exists(), "run harvest first")
        val threshold = System.getenv("SCORE_SPEAKER_PRUNE")?.toDoubleOrNull() ?: 0.98
        var kept = 0
        var dropped = 0
        for (charDir in glyphDir.listFiles()!!.sortedBy { it.name }) {
            val keepers = mutableListOf<Mat>()
            var charDropped = 0
            for (file in charDir.listFiles()!!.sortedBy { it.name }) {
                val mat = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
                val normalised = normaliseGlyph(mat)
                mat.release()
                val duplicate = keepers.any { similarity(normalised, it) >= threshold }
                if (duplicate) {
                    file.delete()
                    normalised.release()
                    charDropped++
                } else {
                    keepers += normalised
                }
            }
            kept += keepers.size
            dropped += charDropped
            println("${charDir.name}: kept ${keepers.size}, dropped $charDropped")
            keepers.forEach { it.release() }
        }
        println("prune @ $threshold: kept $kept, dropped $dropped")
    }

    /** NCC of two same-height glyphs, the second resized onto the first. */
    private fun similarity(a: Mat, b: Mat): Double {
        val resized = Mat()
        Imgproc.resize(b, resized, Size(a.cols().toDouble(), a.rows().toDouble()))
        val result = Mat()
        return try {
            Imgproc.matchTemplate(a, resized, result, Imgproc.TM_CCOEFF_NORMED)
            Core.minMaxLoc(result).maxVal
        } finally {
            result.release()
            resized.release()
        }
    }

    /** Rescale to the matcher's normalised height, preserving aspect ratio. */
    private fun normaliseGlyph(image: Mat, height: Int = 60): Mat {
        val out = Mat()
        val w = (image.cols() * height.toDouble() / image.rows()).toInt().coerceAtLeast(1)
        Imgproc.resize(image, out, Size(w.toDouble(), height.toDouble()))
        return out
    }

    /**
     * Leave-one-case-out accuracy — the honest number.
     *
     * [harvestSessionLists] takes its templates from cell C of the same
     * frames whose cell D [evaluate] reads. Different region and render
     * size, but the same exposure, focus and angle, so a straight
     * re-evaluation flatters itself. Here each case is read by an alphabet
     * rebuilt from every glyph *except* the ones that case donated, so no
     * frame ever helps read itself.
     */
    @Test
    fun evaluateHeldOut() {
        CoordinateTransform.ensureOpenCv()
        assumeTrue(glyphDir.exists(), "run harvest first")
        val glyphFiles = glyphDir.listFiles()!!.sortedBy { it.name }
            .flatMap { dir -> (dir.listFiles() ?: emptyArray()).map { dir.name.single() to it } }
        assumeTrue(glyphFiles.isNotEmpty(), "empty alphabet")

        var correct = 0
        var total = 0
        var nanos = 0L
        for (case in TestCase.findAll(corpusDir).sortedBy { it.directory.name }) {
            val expected = case.loadAnnotations().expectedValues["D"] as? ScoreShotValue ?: continue
            val prefix = case.directory.name.take(8)
            val matcher = DigitTemplateMatcher(minScore = 0.5)
            var held = 0
            for ((ch, file) in glyphFiles) {
                if (file.name.startsWith("$prefix-")) { held++; continue }
                val mat = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
                matcher.registerCharacter(ch, mat)
                mat.release()
            }
            val region = shotRegionMat(cellMatFor(case, "D"))
            val started = System.nanoTime()
            val got = try { matcher.recognise(region) ?: "(null)" } finally { region.release() }
            nanos += System.nanoTime() - started
            val want = "${expected.shot}${expected.mode ?: ""}"
            total++
            if (got == want) correct++
            println("$prefix: expected '$want' got '$got' ${if (got == want) "✓" else "✗"} (held out $held)")
        }
        println("held-out accuracy: $correct/$total")
        println("mean read: ${nanos / 1_000_000.0 / total.coerceAtLeast(1)} ms over ${glyphFiles.size} templates")
    }

    @Test
    fun harvest() {
        requireHarvestOptIn()
        CoordinateTransform.ensureOpenCv()
        val case = TestCase.findAll(corpusDir)
            .firstOrNull { it.directory.name.startsWith("9e9f9aa8") }
        assumeTrue(case != null, "harvest case missing")

        val cellC = cellMatFor(case!!, "C")
        val saved = mutableMapOf<Char, Int>()
        try {
            val components = binarizedComponents(cellC)
            // Cluster into rows by y-center; a row gap is > half a glyph height.
            val rows = mutableListOf<MutableList<Pair<Mat, Rect>>>()
            for (comp in components.sortedBy { it.second.y + it.second.height / 2 }) {
                val cy = comp.second.y + comp.second.height / 2
                val row = rows.lastOrNull()?.takeIf { r ->
                    val ry = r.first().second.let { it.y + it.height / 2 }
                    kotlin.math.abs(cy - ry) < comp.second.height
                }
                if (row != null) row += comp else rows += mutableListOf(comp)
            }

            var expectedIdx = 0
            for (row in rows) {
                if (expectedIdx >= listRows.size) break
                val maxH = row.maxOf { it.second.height }
                val glyphs = row.filter { it.second.height >= maxH * 0.5 }.sortedBy { it.second.x }
                // Shot rows binarize to exactly: 2 shot digits, arrow, 2 score
                // digits (decimal blob dropped by the height filter). Letter
                // rows (Delsumma/Total) have far more components.
                if (glyphs.size != 5) continue
                val (shot, score) = listRows[expectedIdx]
                expectedIdx++
                // Row "10" hugs the cell border and its glyphs binarize
                // clipped/filled (the "0" comes out a near-solid blob).
                // It must still consume its expected entry — skipping it
                // silently shifts every later row's labels by one, which
                // mislabeled the entire first harvest — but its glyphs
                // don't enter the alphabet.
                if (shot == "10") continue
                val chars = "$shot?$score" // '?' = the arrow, skipped
                for ((i, g) in glyphs.withIndex()) {
                    val ch = chars[i]
                    if (ch == '?') continue
                    save(ch, g.first, "9e9f9aa8-row$shot-$i")
                    saved.merge(ch, 1, Int::plus)
                }
            }
            components.forEach { it.first.release() }
        } finally {
            cellC.release()
        }

        // P: the trailing glyph of *every* practice case's shot region.
        // Every P case is used, not just the first — with a single
        // instance the alphabet has no P at all once that frame is held
        // out, which is exactly what `evaluateHeldOut` reports. P has no
        // other source: the list panel carries it only in practice
        // sessions, and the corpus has none.
        for (pCase in TestCase.findAll(corpusDir)) {
            val annotations = pCase.loadAnnotations()
            val shot = annotations.expectedValues["D"] as? ScoreShotValue ?: continue
            if (shot.mode != "P") continue
            val expectedGlyphs = shot.shot.length + 1 // digits + the P
            val shotRegion = shotRegionMat(cellMatFor(pCase, "D"))
            try {
                val glyphs = binarizedComponents(shotRegion)
                    .let { comps ->
                        val maxH = comps.maxOfOrNull { it.second.height } ?: return@let emptyList()
                        comps.filter { it.second.height >= maxH * 0.5 }.sortedBy { it.second.x }
                    }
                if (glyphs.size == expectedGlyphs) {
                    save('P', glyphs.last().first, "${pCase.directory.name.take(8)}-shotP")
                    saved.merge('P', 1, Int::plus)
                }
                glyphs.forEach { it.first.release() }
            } finally {
                shotRegion.release()
            }
        }

        println("harvested: ${saved.toSortedMap()}")
        assumeTrue(saved.keys.containsAll(('0'..'9').toList()), "digit coverage incomplete: $saved")
    }

    @Test
    fun evaluate() {
        CoordinateTransform.ensureOpenCv()
        assumeTrue(glyphDir.exists(), "run harvest first")
        val matcher = DigitTemplateMatcher(minScore = 0.5)
        var registered = 0
        for (charDir in glyphDir.listFiles()!!.sortedBy { it.name }) {
            for (glyph in charDir.listFiles()?.sortedBy { it.name } ?: continue) {
                val mat = Imgcodecs.imread(glyph.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
                matcher.registerCharacter(charDir.name.single(), mat)
                mat.release()
                registered++
            }
        }
        println("alphabet: $registered template instances")

        // Annotated corpus cases.
        for (case in TestCase.findAll(corpusDir)) {
            val expected = case.loadAnnotations().expectedValues["D"] as? ScoreShotValue ?: continue
            // No corners requirement: cellMatFor falls back to the
            // production detector, so a case without a hand-annotated quad
            // is still evaluated rather than silently dropped.
            val region = shotRegionMat(cellMatFor(case, "D"))
            val got = try {
                val r = matcher.recognise(region) ?: "(null)"
                val detail = matcher.lastMatches.joinToString(" ") { m ->
                    "${m.char ?: '∅'}=${"%.2f".format(m.score)}${if (m.accepted) "" else "✗"}"
                }
                println("${case.directory.name.take(8)} glyphs: $detail")
                r
            } finally {
                region.release()
            }
            val want = "${expected.shot}${expected.mode ?: ""}"
            println("${case.directory.name.take(8)}: expected '$want' got '$got' ${if (got == want) "✓" else "✗"}")
        }

        // The degraded field capture: truth "8".
        val fieldCrop = File(corpusDir, "field-20260723-shot8-misread/crops/D.png")
        if (fieldCrop.exists()) {
            val cell = Imgcodecs.imread(fieldCrop.absolutePath)
            val region = shotRegionMat(cell)
            cell.release()
            val got = try {
                matcher.recognise(region) ?: "(null)"
            } finally {
                region.release()
            }
            println("field-20260723 (truth '8', historically misread as '10'): got '$got' ${if (got == "8") "✓" else "✗"}")
        }
    }

    /**
     * Harvest the shot region of *field captures* — capture dirs named
     * `field-<date>-shot<N>[P]…` in the corpus dir, truth encoded in the
     * directory name (the human labels by renaming the pulled capture).
     * Shot glyphs → shot-font. (No score-font: scores are derived from the
     * green marker geometrically, never read from the digits.)
     *
     * Dirs tagged `-misread` are **benchmarks and never harvested** —
     * train/test separation: a capture the integration tests read must not
     * donate the very templates that read it.
     */
    @Test
    fun harvestFieldCaptures() {
        requireHarvestOptIn()
        CoordinateTransform.ensureOpenCv()
        val shotLabel = Regex("shot(\\d{1,2}P?)")
        var harvested = 0
        for (dir in testDataDir.listFiles()!!.filter { it.isDirectory && it.name.startsWith("field-") }) {
            if (dir.name.contains("misread")) continue
            val crop = File(dir, "crops/D.png")
            if (!crop.exists()) continue
            val cell = Imgcodecs.imread(crop.absolutePath)
            try {
                shotLabel.find(dir.name)?.groupValues?.get(1)?.let { label ->
                    harvested += harvestRegion(
                        cell, CellDExtractor.shotRect(cell.cols(), cell.rows()),
                        label, "shot-font", dir.name,
                    )
                }
            } finally {
                cell.release()
            }
        }
        println("field harvest: $harvested glyphs")
    }

    /**
     * Segment [rect] of [cell] (channel-reduced per [reduce]) and save its
     * glyphs when the component count matches [label] exactly — anything
     * else is ambiguous and skipped rather than guessed at.
     */
    private fun harvestRegion(cell: Mat, rect: org.opencv.core.Rect, label: String, font: String, source: String): Int {
        val crop = Mat(cell, rect)
        val reduced = Mat()
        try {
            // min-RGB channel reduce: white shot digits survive, color casts don't.
            if (cell.channels() == 1) {
                crop.copyTo(reduced)
            } else {
                val channels = ArrayList<Mat>()
                Core.split(crop, channels)
                Core.min(channels[0], channels[1], reduced)
                Core.min(reduced, channels[2], reduced)
                channels.forEach { it.release() }
            }
            val comps = binarizedComponents(reduced)
            val maxH = comps.maxOfOrNull { it.second.height } ?: return 0
            val glyphs = comps.filter { it.second.height >= maxH * 0.5 }.sortedBy { it.second.x }
            val saved = if (glyphs.size == label.length) {
                for ((i, g) in glyphs.withIndex()) {
                    saveTo(font, label[i], g.first, "$source-$i")
                }
                label.length
            } else {
                println("  $source/$font: ${glyphs.size} glyphs vs label '$label' — skipped")
                0
            }
            comps.forEach { it.first.release() }
            return saved
        } finally {
            reduced.release()
            crop.release()
        }
    }


    private fun saveTo(font: String, ch: Char, binGlyph: Mat, name: String) {
        val dir = File(File(testDataDir, "glyphs/$font"), ch.toString()).apply { mkdirs() }
        Imgcodecs.imwrite(File(dir, "$name.png").absolutePath, binGlyph)
    }

    private fun save(ch: Char, binGlyph: Mat, name: String) {
        val dir = File(glyphDir, ch.toString()).apply { mkdirs() }
        Imgcodecs.imwrite(File(dir, "$name.png").absolutePath, binGlyph)
    }

    /** Connected components of the Otsu-binarized (foreground-white) [mat]. */
    private fun binarizedComponents(mat: Mat): List<Pair<Mat, Rect>> {
        val gray = Mat()
        val binary = Mat()
        try {
            if (mat.channels() > 1) {
                // min(R,G,B): white text stays bright, green bleed collapses.
                val channels = ArrayList<Mat>()
                Core.split(mat, channels)
                Core.min(channels[0], channels[1], gray)
                Core.min(gray, channels[2], gray)
                channels.forEach { it.release() }
            } else {
                mat.copyTo(gray)
            }
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            if (Core.countNonZero(binary) > binary.rows() * binary.cols() / 2) {
                Core.bitwise_not(binary, binary)
            }
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val count = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids)
            val out = mutableListOf<Pair<Mat, Rect>>()
            val area = binary.rows() * binary.cols()
            for (i in 1 until count) {
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val a = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()
                if (a < area / 4000) continue
                if (w >= binary.cols() - 2 || h >= binary.rows() - 2) continue
                out += Mat(binary, Rect(x, y, w, h)).clone() to Rect(x, y, w, h)
            }
            labels.release()
            stats.release()
            centroids.release()
            return out
        } finally {
            gray.release()
            binary.release()
        }
    }

    /**
     * Shot region reduced via min(R,G,B) — white digits stay bright, green
     * score bleed collapses; same reduction the harvest and CellDExtractor
     * use, so segmentation is consistent end to end.
     */
    private fun shotRegionMat(cellMat: Mat): Mat {
        try {
            val crop = Mat(cellMat, CellDExtractor.shotRect(cellMat.cols(), cellMat.rows()))
            try {
                if (crop.channels() == 1) return crop.clone()
                val channels = ArrayList<Mat>()
                Core.split(crop, channels)
                val reduced = Mat()
                Core.min(channels[0], channels[1], reduced)
                Core.min(reduced, channels[2], reduced)
                channels.forEach { it.release() }
                return reduced
            } finally {
                crop.release()
            }
        } finally {
            cellMat.release()
        }
    }

    /**
     * The rectified [cellName] crop for [case]. Uses the annotated corners
     * when present, otherwise the production detector — a session capture
     * needs no hand-annotated quad to donate glyphs, and detector-quality
     * rectification is the distribution the reader actually sees.
     */
    private fun cellMatFor(case: TestCase, cellName: String): Mat {
        val sourceImage = ImageIO.read(case.sourceFile)
        val corners = case.loadAnnotations().screenCorners
        return ImageSource.fromBufferedImage(sourceImage, sourcePath = case.sourceFile.absolutePath).use { source ->
            val detected = if (corners != null) {
                DetectedScreen(source, corners.toQuadrilateral(), 1.0f, "manual")
            } else {
                requireNotNull(detector.detect(source)) { "detection failed for ${case.directory.name}" }
            }
            detected.rectifyAtDetectedResolution().use { view ->
                view.withSiusCells().extractCellAsMat(cellName)!!
            }
        }
    }

    /** The production detector, for cases with no hand-annotated corners. */
    private val detector: ScreenDetector by lazy {
        CoordinateTransform.ensureOpenCv()
        MultiScaleMatcher(scales = listOf(0.3, 0.5, 0.7, 1.0), maxFeatures = 500, minInliers = 8, maxInputWidth = 2500)
            .apply { loadTemplate(ImageBridge.toMat(ImageIO.read(File(testDataDir, "masked-template.png")))) }
    }
}
