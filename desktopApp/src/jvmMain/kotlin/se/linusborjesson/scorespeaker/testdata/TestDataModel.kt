package se.linusborjesson.scorespeaker.testdata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.linusborjesson.scorespeaker.cells.CellValue
import se.linusborjesson.scorespeaker.pipeline.Point2D
import se.linusborjesson.scorespeaker.pipeline.Quadrilateral
import java.io.File

/**
 * JSON serialization configuration for test data files.
 */
val testDataJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ============ Annotations (input) ============

/**
 * A 2D point for JSON serialization.
 */
@Serializable
data class JsonPoint(val x: Double, val y: Double)

/**
 * Screen corners in image coordinates.
 */
@Serializable
data class ScreenCorners(
    val topLeft: JsonPoint,
    val topRight: JsonPoint,
    val bottomRight: JsonPoint,
    val bottomLeft: JsonPoint
) {
    fun toList(): List<JsonPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun toQuadrilateral(): Quadrilateral = Quadrilateral(
        topLeft = Point2D(topLeft.x, topLeft.y),
        topRight = Point2D(topRight.x, topRight.y),
        bottomRight = Point2D(bottomRight.x, bottomRight.y),
        bottomLeft = Point2D(bottomLeft.x, bottomLeft.y),
    )
}

/**
 * Annotations for a test image.
 *
 * @param screenCorners The four corners of the screen in image coordinates
 * @param expectedValues Map of cell name to expected structured value
 */
@Serializable
data class Annotations(
    val screenCorners: ScreenCorners? = null,
    val expectedValues: Map<String, CellValue> = emptyMap()
) {
    companion object {
        fun load(file: File): Annotations? {
            if (!file.exists()) return null
            return try {
                testDataJson.decodeFromString(serializer(), file.readText())
            } catch (e: Exception) {
                println("Warning: Failed to parse ${file.absolutePath}: ${e.message}")
                null
            }
        }

        fun loadOrEmpty(file: File): Annotations {
            return load(file) ?: Annotations()
        }
    }

    fun save(file: File) {
        file.writeText(testDataJson.encodeToString(serializer(), this))
    }
}

// ============ Processing Results (output) ============

/**
 * Detection result details.
 */
@Serializable
data class DetectionResult(
    val success: Boolean,
    val method: String,
    val confidence: Double,
    val cornersDetected: ScreenCorners? = null,
    val cornersExpected: ScreenCorners? = null,
    val cornerErrorPixels: Double? = null,
    val matchCount: Int = 0,
    val inlierCount: Int = 0,
    val failureReason: String? = null
)

/**
 * Result for a single cell. `match` reflects semantic equality (per
 * [CellValue.matches]), while `extracted` and `expected` are display strings
 * for human inspection.
 */
@Serializable
data class CellResult(
    val extracted: String?,
    val expected: String?,
    val match: Boolean?
) {
    companion object {
        fun compare(extracted: CellValue?, expected: CellValue?): CellResult {
            val match = when {
                expected == null -> null
                extracted == null -> false
                else -> expected.matches(extracted)
            }
            return CellResult(
                extracted = extracted?.displayString(),
                expected = expected?.displayString(),
                match = match,
            )
        }
    }
}

/**
 * Summary statistics.
 */
@Serializable
data class ResultSummary(
    val cellsTotal: Int,
    val cellsWithExpectations: Int,
    val cellsCorrect: Int,
    val accuracy: Double?
)

/**
 * Complete processing result for a test case.
 */
@Serializable
data class ProcessingResult(
    val source: String,
    val timestamp: String,
    val detection: DetectionResult,
    val cells: Map<String, CellResult> = emptyMap(),
    val summary: ResultSummary
) {
    companion object {
        fun load(file: File): ProcessingResult? {
            if (!file.exists()) return null
            return try {
                testDataJson.decodeFromString(serializer(), file.readText())
            } catch (e: Exception) {
                null
            }
        }
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(testDataJson.encodeToString(serializer(), this))
    }
}

// ============ Metadata ============

/**
 * Metadata for a test case.
 */
@Serializable
data class TestCaseMetadata(
    val originalFileName: String,
    val importedAt: String,
    val description: String? = null
) {
    companion object {
        fun load(file: File): TestCaseMetadata? {
            if (!file.exists()) return null
            return try {
                testDataJson.decodeFromString(serializer(), file.readText())
            } catch (e: Exception) {
                null
            }
        }
    }

    fun save(file: File) {
        file.writeText(testDataJson.encodeToString(serializer(), this))
    }
}

// ============ Test Case ============

/**
 * Represents a test case directory with source image and annotations.
 */
data class TestCase(
    val directory: File
) {
    val id: String get() = directory.name
    val sourceFile: File get() = File(directory, "source.png")
    val metadataFile: File get() = File(directory, "metadata.json")
    val annotationsFile: File get() = File(directory, "annotations.json")
    val outputDir: File get() = File(directory, "output")
    val resultFile: File get() = File(outputDir, "result.json")
    val rectifiedFile: File get() = File(outputDir, "rectified.png")
    val cellsDir: File get() = File(outputDir, "cells")

    val exists: Boolean get() = sourceFile.exists()

    /** Display name - original filename from metadata, or folder name as fallback */
    val displayName: String get() = loadMetadata()?.originalFileName ?: id

    fun loadMetadata(): TestCaseMetadata? = TestCaseMetadata.load(metadataFile)

    fun saveMetadata(metadata: TestCaseMetadata) {
        metadata.save(metadataFile)
    }

    fun loadAnnotations(): Annotations = Annotations.loadOrEmpty(annotationsFile)

    fun saveAnnotations(annotations: Annotations) {
        annotations.save(annotationsFile)
    }

    companion object {
        /**
         * Find all test cases in a directory.
         */
        fun findAll(testDataDir: File): List<TestCase> {
            if (!testDataDir.exists() || !testDataDir.isDirectory) return emptyList()
            return testDataDir.listFiles()
                ?.filter { it.isDirectory && File(it, "source.png").exists() }
                ?.map { TestCase(it) }
                ?.sortedByDescending { it.loadMetadata()?.importedAt ?: it.id }
                ?: emptyList()
        }

        /**
         * Create a new test case with UUID folder name.
         */
        fun create(testDataDir: File, originalFileName: String): TestCase {
            val uuid = java.util.UUID.randomUUID().toString()
            val caseDir = File(testDataDir, uuid)
            caseDir.mkdirs()

            val testCase = TestCase(caseDir)

            // Save metadata
            val metadata = TestCaseMetadata(
                originalFileName = originalFileName,
                importedAt = java.time.Instant.now().toString()
            )
            testCase.saveMetadata(metadata)

            return testCase
        }
    }
}
