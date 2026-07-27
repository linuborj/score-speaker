package se.linusborjesson.scorespeaker.pipeline

import org.opencv.core.Mat

/**
 * Locates the SIUS display in a frame. Implementations should be safe to call
 * repeatedly with the same loaded template — that's the common case in
 * continuous reading (point camera once, decode many frames).
 */
interface ScreenDetector {
    /** Detect the screen in [source]. Returns null when not found / too low confidence. */
    fun detect(source: ImageSource): DetectedScreen?

    /**
     * Load a template Mat. Magenta (255, 0, 255) regions are masked off so
     * dynamic content (timestamps, scores) doesn't pollute feature matching.
     */
    fun loadTemplate(template: Mat)

    /** Loaded template's dimensions, or null if no template loaded. */
    fun templateSize(): Size2D?
}
