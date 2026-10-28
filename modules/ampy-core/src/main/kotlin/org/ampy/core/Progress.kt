package org.ampy.core

/** Eccezione lanciata quando l'utente interrompe un'elaborazione. */
class CancelledException : RuntimeException("Operazione annullata dall'utente")

/** Token di cancellazione cooperativa, condivisibile tra thread. */
class CancellationToken {
    @Volatile var isCancelled: Boolean = false
        private set
    fun cancel() { isCancelled = true }
    fun throwIfCancelled() { if (isCancelled) throw CancelledException() }

    companion object { val NONE = CancellationToken() }
}

/** Riceve avanzamento reale (0..1) di un'elaborazione. */
fun interface ProgressSink {
    fun report(fraction: Double, message: String)

    companion object { val NONE = ProgressSink { _, _ -> } }
}

/** Ridistribuisce un sotto-intervallo [from, to] dell'avanzamento totale (per pipeline a fasi). */
fun ProgressSink.sub(from: Double, to: Double): ProgressSink = ProgressSink { f, m ->
    this.report(from + (to - from) * f.coerceIn(0.0, 1.0), m)
}

/** Contesto di un job: progresso + cancellazione. */
class JobContext(
    val progress: ProgressSink = ProgressSink.NONE,
    val cancel: CancellationToken = CancellationToken(),
)
