package org.ampy.transcriber

import org.ampy.core.JobContext
import org.ampy.core.NoteEvent
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Trascrittore monofonico DSP (algoritmo YIN, de Cheveigné & Kawahara 2002).
 * Non richiede modelli: è il fallback quando Basic Pitch non è installato, adatto a voce, basso e fiati solisti.
 */
class MonoPitchTranscriber(
    val sampleRate: Int,
    val frameSize: Int = 2048,
    val hop: Int = 256,
    val minHz: Double = 50.0,
    val maxHz: Double = 1500.0,
    val threshold: Double = 0.15,
    val minNoteSec: Double = 0.08,
    val silenceDb: Double = -45.0,
) {
    data class Frame(val timeSec: Double, val hz: Double?, val periodicity: Double, val rmsDb: Double)

    fun track(input: FloatArray, ctx: JobContext = JobContext()): List<Frame> {
        // padding centrato (come librosa center=True): il frame k è centrato su k*hop
        val pad = frameSize / 2
        val x = FloatArray(input.size + 2 * pad).also { input.copyInto(it, pad) }
        val maxLag = minOf(frameSize / 2, (sampleRate / minHz).toInt())
        val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(2)
        val d = DoubleArray(maxLag + 1); val cmnd = DoubleArray(maxLag + 1)
        val out = ArrayList<Frame>()
        var start = 0; var n = 0
        val total = maxOf(1, (x.size - frameSize) / hop)
        while (start + frameSize <= x.size) {
            if (n % 200 == 0) { ctx.cancel.throwIfCancelled(); ctx.progress.report(n.toDouble() / total, "Tracciamento altezza (YIN)") }
            var e = 0.0
            for (i in 0 until frameSize) e += x[start + i] * x[start + i]
            val rmsDb = 10 * log10(e / frameSize + 1e-12)
            for (tau in 1..maxLag) {
                var s = 0.0
                val w = frameSize - maxLag
                for (i in 0 until w) { val df = x[start + i] - x[start + i + tau]; s += df * df }
                d[tau] = s
            }
            cmnd[0] = 1.0; var run = 0.0
            for (tau in 1..maxLag) { run += d[tau]; cmnd[tau] = if (run > 0) d[tau] * tau / run else 1.0 }
            var tauEst = -1
            for (tau in minLag..maxLag) {
                if (cmnd[tau] < threshold) {
                    var t = tau
                    while (t + 1 <= maxLag && cmnd[t + 1] < cmnd[t]) t++
                    tauEst = t; break
                }
            }
            val time = start.toDouble() / sampleRate
            if (tauEst > 0 && rmsDb > silenceDb) {
                // interpolazione parabolica
                val t = tauEst
                val better = if (t in 1 until maxLag) {
                    val a = cmnd[t - 1]; val b = cmnd[t]; val c = cmnd[t + 1]
                    val den = a - 2 * b + c
                    if (den != 0.0) t + 0.5 * (a - c) / den else t.toDouble()
                } else t.toDouble()
                out.add(Frame(time, sampleRate / better, 1 - cmnd[t], rmsDb))
            } else out.add(Frame(time, null, 0.0, rmsDb))
            start += hop; n++
        }
        return out
    }

    fun transcribe(x: FloatArray, ctx: JobContext = JobContext()): List<NoteEvent> {
        val frames = track(x, ctx)
        val midi = frames.map { f -> f.hz?.let { 69 + 12 * ln(it / 440.0) / ln(2.0) } }
        // filtro mediano su 5 frame per eliminare salti d'ottava isolati
        val smooth = midi.indices.map { i ->
            val w = (maxOf(0, i - 2)..minOf(midi.size - 1, i + 2)).mapNotNull { midi[it] }
            if (midi[i] == null || w.size < 3) midi[i] else w.sorted()[w.size / 2]
        }
        val notes = ArrayList<NoteEvent>()
        var i = 0
        val frameSec = hop.toDouble() / sampleRate
        while (i < smooth.size) {
            val m = smooth[i]
            if (m == null) { i++; continue }
            val p = m.roundToInt()
            var j = i; var sumConf = 0.0; var maxDb = -200.0
            while (j < smooth.size && smooth[j] != null && kotlin.math.abs(smooth[j]!! - p) < 0.6) {
                sumConf += frames[j].periodicity; maxDb = maxOf(maxDb, frames[j].rmsDb); j++
            }
            val dur = (j - i) * frameSec
            if (dur >= minNoteSec && p in 0..127) {
                val vel = (127 * ((maxDb + 60) / 60)).roundToInt().coerceIn(20, 127)
                notes.add(NoteEvent(p, frames[i].timeSec - frameSec / 2, frames[j - 1].timeSec + frameSec / 2, vel, (sumConf / (j - i)).toFloat().coerceIn(0f, 1f)))
            }
            i = maxOf(j, i + 1)
        }
        return notes
    }

    companion object {
        fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / maxOf(1, x.size))
    }
}
