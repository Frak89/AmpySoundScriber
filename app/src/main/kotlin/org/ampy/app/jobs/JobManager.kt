package org.ampy.app.jobs

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ampy.core.AmpyLog
import org.ampy.core.CancellationToken
import org.ampy.core.CancelledException
import org.ampy.core.JobContext
import org.ampy.core.LogCategory
import org.ampy.core.ProgressSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class JobStatus { RUNNING, DONE, FAILED, CANCELLED }

data class JobState(
    val id: Int, val title: String, val progress: Double = 0.0, val message: String = "",
    val status: JobStatus = JobStatus.RUNNING, val error: String? = null,
    val startedAtMs: Long = System.currentTimeMillis(), val endedAtMs: Long? = null,
)

/**
 * Esegue le elaborazioni pesanti fuori dal thread UI con avanzamento reale e annullamento.
 * Su Android 15+ avvia un servizio in primo piano "mediaProcessing" per non perdere il lavoro a schermo spento.
 */
class JobManager(private val app: Context, private val scope: CoroutineScope) {
    private val _jobs = MutableStateFlow<List<JobState>>(emptyList())
    val jobs: StateFlow<List<JobState>> = _jobs.asStateFlow()
    private val tokens = ConcurrentHashMap<Int, CancellationToken>()
    private val ids = AtomicInteger(1)

    val running: List<JobState> get() = _jobs.value.filter { it.status == JobStatus.RUNNING }

    /** [longRunning]: avvia il servizio in primo piano se il job dura più di qualche secondo. */
    fun launch(title: String, longRunning: Boolean = false, onDone: (Boolean) -> Unit = {}, block: suspend (JobContext) -> Unit): Int {
        val id = ids.getAndIncrement()
        val token = CancellationToken()
        tokens[id] = token
        _jobs.update { (listOf(JobState(id, title)) + it).take(30) }
        if (longRunning) {
            longJobs.add(id)
            scope.launch { delay(1500); if (id in longJobs) updateService() }
        }
        var lastEmit = 0L
        val progress = ProgressSink { f, m ->
            val now = System.currentTimeMillis()
            if (now - lastEmit > 120 || f >= 1.0) {
                lastEmit = now
                _jobs.update { l -> l.map { if (it.id == id) it.copy(progress = f.coerceIn(0.0, 1.0), message = m) else it } }
                if (serviceStarted && id in longJobs) ProcessingService.updateProgress(app, title, f, m)
            }
        }
        scope.launch(Dispatchers.Default) {
            val t0 = System.nanoTime()
            var ok = false
            try {
                block(JobContext(progress, token))
                finish(id, JobStatus.DONE, null); ok = true
                AmpyLog.i(LogCategory.TIMING, "job", "$title completato in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s")
            } catch (e: CancelledException) {
                finish(id, JobStatus.CANCELLED, null)
                AmpyLog.i(LogCategory.GENERAL, "job", "$title annullato")
            } catch (e: OutOfMemoryError) {
                finish(id, JobStatus.FAILED, "Memoria esaurita: ridurre la dimensione dei segmenti nelle impostazioni")
                AmpyLog.e(LogCategory.MEMORY, "job", "$title: OutOfMemoryError", e)
            } catch (e: Throwable) {
                finish(id, JobStatus.FAILED, "${e.javaClass.simpleName}: ${e.message}")
                AmpyLog.e(LogCategory.GENERAL, "job", "$title fallito: ${e.message}", e)
            } finally {
                tokens.remove(id)
                if (longJobs.remove(id)) updateService()
                runCatching { onDone(ok) }
            }
        }
        return id
    }

    private fun finish(id: Int, status: JobStatus, error: String?) {
        _jobs.update { l -> l.map { if (it.id == id) it.copy(status = status, error = error, progress = if (status == JobStatus.DONE) 1.0 else it.progress, endedAtMs = System.currentTimeMillis()) else it } }
    }

    fun cancel(id: Int) { tokens[id]?.cancel() }
    fun cancelAll() { tokens.values.forEach { it.cancel() } }
    fun clearFinished() = _jobs.update { l -> l.filter { it.status == JobStatus.RUNNING } }

    private val longJobs = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    @Volatile private var serviceStarted = false

    @Synchronized private fun updateService() {
        if (Build.VERSION.SDK_INT < 35) return // il tipo mediaProcessing esiste da Android 15
        val want = longJobs.isNotEmpty()
        if (want == serviceStarted) return
        runCatching {
            val i = Intent(app, ProcessingService::class.java)
            if (want) app.startForegroundService(i) else app.stopService(i)
            serviceStarted = want
        }.onFailure { AmpyLog.w(LogCategory.GENERAL, "job", "Servizio in primo piano non avviabile: ${it.message}") }
    }
}
