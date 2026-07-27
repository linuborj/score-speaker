package se.linusborjesson.scorespeaker.testdata

import java.io.File

/**
 * Locate the in-repo `desktopApp/test-data` directory — the *bundled*
 * test assets: the masked detection template and the glyph alphabet.
 * These are clean (no personal data) and ship with the repository.
 *
 * Tries a handful of likely spots so the helper works from the gradle
 * build dir, from the project root, and from arbitrary working
 * directories. Returns the first candidate that exists, or
 * `desktopApp/test-data` relative to the working dir as a stable
 * fallback.
 */
fun findTestDataDir(): File {
    val candidates = listOf(
        File("desktopApp/test-data"),
        File("test-data"),
        File(System.getProperty("user.dir"), "desktopApp/test-data"),
        File(System.getProperty("user.dir"), "test-data"),
    )
    return candidates.firstOrNull { it.exists() && it.isDirectory }
        ?: File("desktopApp/test-data")
}

/**
 * Locate the private capture corpus — the annotated real-photo test
 * cases (one directory per case: `source.png` + `annotations.json` +
 * `metadata.json`, plus field captures). The corpus is range photos and
 * is deliberately NOT part of the repository; it lives outside the
 * working tree and is resolved from:
 *
 *   1. the `SCORE_SPEAKER_CORPUS` environment variable, if set
 *   2. `~/score-speaker-corpus` otherwise
 *
 * The directory may not exist (fresh clone, CI) — corpus-dependent
 * tests and tools treat that as "no cases" and skip.
 */
fun findCorpusDir(): File =
    System.getenv("SCORE_SPEAKER_CORPUS")?.let { File(it) }
        ?: File(System.getProperty("user.home"), "score-speaker-corpus")
