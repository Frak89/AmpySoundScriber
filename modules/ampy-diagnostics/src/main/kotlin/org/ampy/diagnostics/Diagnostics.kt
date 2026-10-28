package org.ampy.diagnostics

import org.ampy.core.AmpyLog
import org.ampy.core.LogCategory
import org.ampy.core.LogLevel
import org.ampy.core.LogRecord
import org.ampy.core.LogSink
import org.ampy.core.ModuleInfo
import org.ampy.core.jsonObj
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsModule {
    val info = ModuleInfo("AmpyDiagnostics", "0.1.0", "Logging strutturato, crash handler, memoria, analisi log")
}

/** Sonda di memoria fornita dalla piattaforma (Android: ActivityManager/Debug). */
fun interface MemoryProbe {
    /** Ritorna (usata, massima) in byte. */
    fun sample(): Pair<Long, Long>

    companion object {
        val JVM = MemoryProbe {
            val rt = Runtime.getRuntime()
            (rt.totalMemory() - rt.freeMemory()) to rt.maxMemory()
        }
    }
}

/**
 * Hub diagnostico: ring buffer in memoria per il pannello UI + file di log ruotati su disco.
 */
class DiagnosticsHub(
    private val logDir: File?,
    private val capacity: Int = 5000,
    private val maxFileBytes: Long = 2L * 1024 * 1024,
    private val memoryProbe: MemoryProbe = MemoryProbe.JVM,
) : LogSink {
    private val ring = ArrayDeque<LogRecord>()
    private val lock = Any()
    private val listeners = mutableListOf<(LogRecord) -> Unit>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    init { logDir?.mkdirs() }

    val currentLogFile: File? get() = logDir?.let { File(it, "ampy.log") }

    override fun write(record: LogRecord) {
        val snapshot: List<(LogRecord) -> Unit>
        synchronized(lock) {
            ring.addLast(record)
            if (ring.size > capacity) ring.removeFirst()
            snapshot = listeners.toList()
            appendToFile(record)
        }
        snapshot.forEach { runCatching { it(record) } }
    }

    fun addListener(l: (LogRecord) -> Unit) = synchronized(lock) { listeners.add(l) }
    fun removeListener(l: (LogRecord) -> Unit) = synchronized(lock) { listeners.remove(l) }

    fun records(minLevel: LogLevel = LogLevel.DEBUG, category: LogCategory? = null): List<LogRecord> = synchronized(lock) {
        ring.filter { it.level.ordinal >= minLevel.ordinal && (category == null || it.category == category) }
    }

    fun clear() = synchronized(lock) { ring.clear() }

    fun format(r: LogRecord): String = buildString {
        append(fmt.format(Date(r.timeMs))).append(' ').append(r.level.name.padEnd(5)).append(' ')
        append(r.category.name).append('/').append(r.tag).append(" [").append(r.thread).append("] ").append(r.message)
        r.throwable?.let { append('\n').append(it) }
    }

    private fun appendToFile(r: LogRecord) {
        val f = currentLogFile ?: return
        runCatching {
            if (f.length() > maxFileBytes) {
                val old = File(f.parentFile, "ampy.1.log")
                old.delete(); f.renameTo(old)
            }
            FileWriter(f, true).use { it.write(format(r)); it.write("\n") }
        }
    }

    /** Campiona la memoria e la registra (categoria MEMORY). */
    fun sampleMemory(tag: String = "mem"): Pair<Long, Long> {
        val (used, max) = memoryProbe.sample()
        AmpyLog.log(LogLevel.DEBUG, LogCategory.MEMORY, tag, "RAM usata ${used / 1048576} MB / max ${max / 1048576} MB")
        return used to max
    }

    /** Esporta un report leggibile (testo) con intestazione di sistema. */
    fun exportReport(dest: File, systemInfo: Map<String, String>) {
        dest.bufferedWriter().use { w ->
            w.write("AmpySoundScriber — report diagnostico\n")
            w.write(jsonObj(*systemInfo.map { it.key to it.value }.toTypedArray()).encode()); w.write("\n\n")
            val recs = records()
            val analysis = LogAnalyzer.analyze(recs.joinToString("\n") { format(it) })
            w.write("== Analisi automatica ==\n"); w.write(analysis.toText()); w.write("\n\n== Log ==\n")
            recs.forEach { w.write(format(it)); w.write("\n") }
        }
    }

    /** Installa il gestore di eccezioni non catturate (le registra e inoltra al gestore precedente). */
    fun installCrashHandler(onCrash: (Thread, Throwable) -> Unit = { _, _ -> }) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AmpyLog.e(LogCategory.NATIVE, "crash", "Eccezione non gestita nel thread ${t.name}: ${e.javaClass.name}: ${e.message}", e)
            runCatching { onCrash(t, e) }
            prev?.uncaughtException(t, e)
        }
    }
}

/** Analisi automatica di un log: errore principale, cause probabili, file coinvolti, correzione verificabile. */
object LogAnalyzer {
    data class Finding(
        val primaryError: String?,
        val probableCauses: List<String>,
        val files: List<String>,
        val suggestedFix: List<String>,
        val errorCount: Int,
        val warningCount: Int,
    ) {
        fun toText(): String = buildString {
            appendLine("Errore principale: ${primaryError ?: "nessun errore rilevato"}")
            appendLine("Errori: $errorCount, avvisi: $warningCount")
            if (probableCauses.isNotEmpty()) appendLine("Cause probabili:\n" + probableCauses.joinToString("\n") { " - $it" })
            if (files.isNotEmpty()) appendLine("File interessati:\n" + files.joinToString("\n") { " - $it" })
            if (suggestedFix.isNotEmpty()) appendLine("Correzioni / verifiche:\n" + suggestedFix.joinToString("\n") { " - $it" })
        }
    }

    private data class Rule(val pattern: Regex, val cause: String, val fix: String)

    private val rules = listOf(
        Rule(Regex("OutOfMemoryError|ENOMEM|bad_alloc", RegexOption.IGNORE_CASE),
            "Memoria esaurita durante un'elaborazione", "Ridurre la dimensione dei segmenti (Impostazioni → Prestazioni) o usare la modalità veloce; verificare con un file breve."),
        Rule(Regex("OrtException|ORT_|onnxruntime", RegexOption.IGNORE_CASE),
            "Errore del motore ONNX Runtime (modello incompatibile, input con forma errata o provider non supportato)", "Verificare il manifest del modello (forme I/O) nel Gestore modelli e riprovare con dispositivo CPU."),
        Rule(Regex("NNAPI|nnapi", RegexOption.IGNORE_CASE),
            "Il delegato NNAPI non supporta alcuni operatori del modello", "Impostare il backend su CPU/XNNPACK; NNAPI è deprecato su Android 15+."),
        Rule(Regex("MediaCodec|MediaExtractor|IllegalStateException.*codec|Unsupported.*mime", RegexOption.IGNORE_CASE),
            "Decoder audio non disponibile o file corrotto", "Convertire il file in WAV/FLAC e reimportarlo; controllare il tipo MIME riportato nel log AUDIO_IMPORT."),
        Rule(Regex("FileNotFoundException|ENOENT|No such file", RegexOption.IGNORE_CASE),
            "File mancante o percorso non valido", "Verificare che il file esista e che il permesso SAF sia ancora valido; reimportare il file."),
        Rule(Regex("SecurityException|Permission denial|EACCES", RegexOption.IGNORE_CASE),
            "Permesso negato (microfono o accesso ai file)", "Concedere il permesso dalle impostazioni di sistema e ripetere l'operazione."),
        Rule(Regex("InvalidMidi|MIDI.*(error|invalid)|running status", RegexOption.IGNORE_CASE),
            "Dati MIDI non validi", "Rieseguire l'esportazione e validare il file con un lettore MIDI esterno."),
        Rule(Regex("drift|desync|sync.*(lost|error)", RegexOption.IGNORE_CASE),
            "Deriva di sincronizzazione audio/spartito", "Verificare la latenza riportata da AudioTrack e il tempo/BPM del progetto."),
        Rule(Regex("CancelledException|annullata", RegexOption.IGNORE_CASE),
            "Operazione interrotta dall'utente", "Nessuna correzione necessaria."),
        Rule(Regex("NumberFormatException|JSON non valido", RegexOption.IGNORE_CASE),
            "File di progetto o manifest danneggiato", "Ripristinare dall'ultimo salvataggio automatico (cartella autosave)."),
    )

    private val frameRegex = Regex("""at ([\w.$]+)\.([\w$<>]+)\(([\w.]+):(\d+)\)""")

    fun analyze(log: String): Finding {
        val lines = log.lines()
        val errorLines = lines.filter { it.contains(" ERROR ") || it.contains("Exception") || it.contains("Error:") || it.startsWith("E/") }
        val warnCount = lines.count { it.contains(" WARN ") || it.startsWith("W/") }
        val primary = errorLines.firstOrNull { it.contains(" ERROR ") } ?: errorLines.firstOrNull()
        val causes = LinkedHashSet<String>(); val fixes = LinkedHashSet<String>()
        // le regole vengono valutate prima sull'errore principale, poi sull'intero log
        for (scope in listOfNotNull(primary, log)) for (r in rules) if (r.pattern.containsMatchIn(scope)) { causes.add(r.cause); fixes.add(r.fix) }
        val files = frameRegex.findAll(log)
            .filter { it.groupValues[1].startsWith("org.ampy") }
            .map { "${it.groupValues[3]}:${it.groupValues[4]} (${it.groupValues[1].substringAfterLast('.')}.${it.groupValues[2]})" }
            .distinct().take(10).toList()
        if (primary != null && causes.isEmpty()) {
            causes.add("Causa non classificata automaticamente")
            fixes.add("Riprodurre il problema con il livello di log DEBUG ed esaminare le righe che precedono l'errore principale.")
        }
        return Finding(primary?.trim(), causes.toList(), files, fixes.toList(), errorLines.size, warnCount)
    }
}
