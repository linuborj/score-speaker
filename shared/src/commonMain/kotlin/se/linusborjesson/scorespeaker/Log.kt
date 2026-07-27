package se.linusborjesson.scorespeaker

/**
 * Minimal pluggable logger. Pipeline code calls [Log.debug]/etc. so we can
 * silence diagnostic output in tests and the Android hot path without ripping
 * `println` out of every algorithm file.
 *
 * Default level is [LogLevel.WARN] — only WARN/ERROR reach stdout. Bump it to
 * [LogLevel.DEBUG] when debugging.
 *
 * Lives in `shared/commonMain` so both modules (and the eventual Android one)
 * can use the same API.
 */
enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, OFF }

object Log {
    @Volatile var level: LogLevel = LogLevel.WARN

    inline fun trace(message: () -> String) { if (level <= LogLevel.TRACE) println("TRACE ${message()}") }
    inline fun debug(message: () -> String) { if (level <= LogLevel.DEBUG) println("DEBUG ${message()}") }
    inline fun info(message: () -> String)  { if (level <= LogLevel.INFO)  println("INFO  ${message()}") }
    inline fun warn(message: () -> String)  { if (level <= LogLevel.WARN)  println("WARN  ${message()}") }
    inline fun error(message: () -> String) { if (level <= LogLevel.ERROR) println("ERROR ${message()}") }
}
