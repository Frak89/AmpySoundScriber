package org.ampy.learninglab

import org.ampy.core.ModuleInfo
import org.ampy.core.NoteEvent
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToLong

object LearningLabModule {
    val info = ModuleInfo("AmpyLearningLab", "0.1.0", "Metriche (compatibili mir_eval), feedback utente, adattamento verificabile dei parametri, export dataset")
}

data class PRF(val precision: Double, val recall: Double, val f1: Double, val matches: Int, val nRef: Int, val nEst: Int) {
    override fun toString() = "P=%.3f R=%.3f F1=%.3f (%d/%d ref, %d stim.)".format(precision, recall, f1, matches, nRef, nEst)
    companion object {
        fun of(matches: Int, nRef: Int, nEst: Int): PRF {
            val p = if (nEst == 0) 0.0 else matches.toDouble() / nEst
            val r = if (nRef == 0) 0.0 else matches.toDouble() / nRef
            val f = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
            return PRF(p, r, f, matches, nRef, nEst)
        }
    }
}

data class TranscriptionReport(
    val noteOnset: PRF,          // altezza + onset (metrica principale "Note F1")
    val noteOnsetOffset: PRF,    // altezza + onset + offset
    val onsetOnly: PRF,          // solo onset ("Onset F1")
    val offsetOnly: PRF,         // solo offset ("Offset F1")
    val pitchAccuracy: Double,   // note corrette (onset+altezza) / note con onset corretto
    val chromaAccuracy: Double,  // come sopra ignorando errori d'ottava
    val meanOnsetErrorMs: Double,// accuratezza ritmica: errore medio di onset delle note corrette
    val runtimeMs: Double? = null,
    val peakMemoryMb: Double? = null,
) {
    fun toText() = buildString {
        appendLine("Note F1 (onset+altezza):        $noteOnset")
        appendLine("Note F1 con offset:             $noteOnsetOffset")
        appendLine("Onset F1:                       $onsetOnly")
        appendLine("Offset F1:                      $offsetOnly")
        appendLine("Accuratezza altezze:            %.3f (croma %.3f)".format(pitchAccuracy, chromaAccuracy))
        appendLine("Errore medio di onset:          %.1f ms".format(meanOnsetErrorMs))
        runtimeMs?.let { appendLine("Tempo di esecuzione:            %.0f ms".format(it)) }
        peakMemoryMb?.let { appendLine("Memoria di picco:               %.0f MB".format(it)) }
    }
}

/**
 * Metriche di trascrizione con le stesse regole di mir_eval.transcription (v0.8):
 * tolleranza onset 50 ms, altezza 50 cent, offset max(20% durata, 50 ms), arrotondamento a 4 decimali,
 * accoppiamento a cardinalità massima (grafo bipartito).
 */
object TranscriptionMetrics {
    private fun round4(x: Double) = (x * 1e4).roundToLong() / 1e4

    private fun maxMatching(nRef: Int, nEst: Int, hit: (Int, Int) -> Boolean): List<Pair<Int, Int>> {
        val adj = Array(nRef) { r -> (0 until nEst).filter { hit(r, it) }.toIntArray() }
        val matchEst = IntArray(nEst) { -1 }
        fun augment(r: Int, seen: BooleanArray): Boolean {
            for (e in adj[r]) {
                if (seen[e]) continue
                seen[e] = true
                if (matchEst[e] < 0 || augment(matchEst[e], seen)) { matchEst[e] = r; return true }
            }
            return false
        }
        for (r in 0 until nRef) if (adj[r].isNotEmpty()) augment(r, BooleanArray(nEst))
        return (0 until nEst).filter { matchEst[it] >= 0 }.map { matchEst[it] to it }
    }

    private fun onsetHit(r: NoteEvent, e: NoteEvent, tol: Double) = round4(abs(r.startSec - e.startSec)) <= tol
    private fun offsetHit(r: NoteEvent, e: NoteEvent, ratio: Double, minTol: Double) = round4(abs(r.endSec - e.endSec)) <= maxOf(ratio * (r.endSec - r.startSec), minTol)

    fun match(ref: List<NoteEvent>, est: List<NoteEvent>, onsetTol: Double = 0.05, withPitch: Boolean = true, withOnset: Boolean = true, withOffset: Boolean = false, offsetRatio: Double = 0.2, offsetMin: Double = 0.05, chroma: Boolean = false) =
        maxMatching(ref.size, est.size) { i, j ->
            (!withPitch || (if (chroma) ref[i].pitch % 12 == est[j].pitch % 12 else ref[i].pitch == est[j].pitch)) &&
                (!withOnset || onsetHit(ref[i], est[j], onsetTol)) &&
                (!withOffset || offsetHit(ref[i], est[j], offsetRatio, offsetMin))
        }

    fun evaluate(ref: List<NoteEvent>, est: List<NoteEvent>, runtimeMs: Double? = null, peakMemoryMb: Double? = null): TranscriptionReport {
        val n = ref.size; val m = est.size
        val noteOn = match(ref, est)
        val noteOnOff = match(ref, est, withOffset = true)
        val onsetOnly = match(ref, est, withPitch = false)
        val offsetOnly = match(ref, est, withPitch = false, withOnset = false, withOffset = true)
        val chromaOn = match(ref, est, chroma = true)
        val onsetErr = noteOn.map { (r, e) -> abs(ref[r].startSec - est[e].startSec) * 1000 }
        return TranscriptionReport(
            PRF.of(noteOn.size, n, m), PRF.of(noteOnOff.size, n, m), PRF.of(onsetOnly.size, n, m), PRF.of(offsetOnly.size, n, m),
            if (onsetOnly.isEmpty()) 0.0 else noteOn.size.toDouble() / onsetOnly.size,
            if (onsetOnly.isEmpty()) 0.0 else chromaOn.size.toDouble() / onsetOnly.size,
            if (onsetErr.isEmpty()) 0.0 else onsetErr.average(), runtimeMs, peakMemoryMb,
        )
    }
}

/** Metriche di separazione: SDR (senza proiezioni di BSS-eval) e SI-SDR. */
object SeparationMetrics {
    fun sdr(ref: FloatArray, est: FloatArray): Double {
        val n = minOf(ref.size, est.size)
        var s = 0.0; var e = 0.0
        for (i in 0 until n) { s += ref[i].toDouble() * ref[i]; val d = ref[i] - est[i]; e += d.toDouble() * d }
        return 10 * log10((s + 1e-12) / (e + 1e-12))
    }

    fun siSdr(ref: FloatArray, est: FloatArray): Double {
        val n = minOf(ref.size, est.size)
        var dot = 0.0; var rr = 0.0
        for (i in 0 until n) { dot += ref[i].toDouble() * est[i]; rr += ref[i].toDouble() * ref[i] }
        val a = dot / (rr + 1e-12)
        var t = 0.0; var noise = 0.0
        for (i in 0 until n) { val target = a * ref[i]; t += target * target; val d = est[i] - target; noise += d * d }
        return 10 * log10((t + 1e-12) / (noise + 1e-12))
    }
}
