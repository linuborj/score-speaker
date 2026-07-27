package se.linusborjesson.scorespeaker.pipeline

import org.opencv.core.Mat

/**
 * Release this Mat after running [block]. Returns whatever the block returns.
 * Use this for short-lived intermediate Mats to avoid native-heap pileups —
 * Android in particular doesn't enjoy waiting for the GC to collect Mats.
 */
inline fun <T> Mat.useMat(block: (Mat) -> T): T {
    try {
        return block(this)
    } finally {
        this.release()
    }
}
