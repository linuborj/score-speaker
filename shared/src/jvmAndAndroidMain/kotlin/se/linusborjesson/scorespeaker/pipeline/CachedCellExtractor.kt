package se.linusborjesson.scorespeaker.pipeline

import org.opencv.core.Mat
import se.linusborjesson.scorespeaker.cells.CellValue

/**
 * Decorator that hashes the cell image and returns the previous result when
 * consecutive frames produce visually-identical input.
 *
 * **Why this matters:** on a live camera the SIUS display changes content
 * once per shot (~30 s apart) while frames arrive at 10–30 fps — ~99.9%
 * of frames would re-read the same pixels to the same answer. Serving the
 * settled value instead is primarily a *stability* property: the trackers
 * downstream see a constant while the content genuinely hasn't changed,
 * rather than a per-frame re-derivation that could flicker on sensor
 * noise. Works for any [CellExtractor] implementation.
 *
 * Hash is a 64-bit *dHash* (difference hash): downsample to 9×8 gray, then
 * each output bit is "left pixel < right pixel". The downsample averages
 * out camera sensor noise; the difference operation is robust to global
 * brightness shifts. Two visually-distinct content variants have Hamming
 * distance > 10 in practice (digits like "7.6" vs "8.6" flip ~25 bits) so
 * [tolerance] up to 5–8 is safe against noise without false hits on real
 * content changes.
 */
class CachedCellExtractor(
    private val delegate: CellExtractor,
    private val tolerance: Int = 5,
) : CellExtractor {

    private var lastHash: Long = SENTINEL
    private var lastValue: CellValue? = null
    private var hits = 0L
    private var misses = 0L

    override fun extract(cellMat: Mat): CellValue? {
        val hash = dHash(cellMat)
        if (lastHash != SENTINEL && hamming(hash, lastHash) <= tolerance) {
            hits++
            return lastValue
        }
        misses++
        val value = delegate.extract(cellMat)
        lastHash = hash
        lastValue = value
        return value
    }

    val hitCount: Long get() = hits
    val missCount: Long get() = misses
    val hitRate: Double get() = if (hits + misses == 0L) 0.0 else hits.toDouble() / (hits + misses)

    fun statsLine(label: String = "cache"): String =
        "$label: hits=$hits misses=$misses (${"%.1f".format(hitRate * 100)}%)"

    fun reset() {
        lastHash = SENTINEL
        lastValue = null
        hits = 0
        misses = 0
    }

    companion object {
        private const val SENTINEL = Long.MIN_VALUE

        internal fun dHash(mat: Mat): Long = ImageHash.dHash(mat)

        internal fun hamming(a: Long, b: Long): Int = ImageHash.hamming(a, b)
    }
}
