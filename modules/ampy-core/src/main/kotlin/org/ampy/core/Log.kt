package org.ampy.core

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Categorie diagnostiche richieste dal capitolato (sezione 10). */
enum class LogCategory { UI, PYTHON, NATIVE, MODEL, AUDIO_IMPORT, MEMORY, TIMING, MIDI, SYNC, SEPARATION, TRANSCRIPTION, COMPOSER, PROJECT, GENERAL }

data class LogRecord(
    val timeMs: Long,
    val level: LogLevel,
    val category: LogCategory,
    val tag: String,
    val message: String,
    val throwable: String? = null,
    val thread: String = Thread.currentThread().name,
)

fun interface LogSink { fun write(record: LogRecord) }

/** Facciata di logging usata da tutti i moduli; AmpyDiagnostics installa il sink reale. */
object AmpyLog {
    @Volatile var sink: LogSink = LogSink { r -> System.err.println("[${r.level}] ${r.category}/${r.tag}: ${r.message}") }
    @Volatile var minLevel: LogLevel = LogLevel.DEBUG

    fun log(level: LogLevel, cat: LogCategory, tag: String, msg: String, t: Throwable? = null) {
        if (level.ordinal < minLevel.ordinal) return
        sink.write(LogRecord(System.currentTimeMillis(), level, cat, tag, msg, t?.stackTraceToString()))
    }
    fun d(cat: LogCategory, tag: String, msg: String) = log(LogLevel.DEBUG, cat, tag, msg)
    fun i(cat: LogCategory, tag: String, msg: String) = log(LogLevel.INFO, cat, tag, msg)
    fun w(cat: LogCategory, tag: String, msg: String, t: Throwable? = null) = log(LogLevel.WARN, cat, tag, msg, t)
    fun e(cat: LogCategory, tag: String, msg: String, t: Throwable? = null) = log(LogLevel.ERROR, cat, tag, msg, t)

    /** Misura e registra il tempo di un blocco (categoria TIMING). */
    inline fun <T> timed(tag: String, label: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally {
            val ms = (System.nanoTime() - t0) / 1e6
            i(LogCategory.TIMING, tag, "$label: ${"%.1f".format(ms)} ms")
        }
    }
}
