package org.ampy.musicbrain

import org.ampy.audioengine.Stft
import org.ampy.core.KeySignature
import org.ampy.core.NoteEvent
import org.ampy.core.Section
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Cromagramma (12 classi di altezza) per frame temporali. */
class Chromagram(val frames: Array<DoubleArray>, val frameSec: Double) {
    companion object {
        /** Da note trascritte: ogni nota contribuisce per durata × velocity. */
        fun fromNotes(notes: List<NoteEvent>, frameSec: Double, durationSec: Double): Chromagram {
            val n = max(1, kotlin.math.ceil(durationSec / frameSec).toInt())
            val fr = Array(n) { DoubleArray(12) }
            for (note in notes) {
                val a = (note.startSec / frameSec).toInt().coerceIn(0, n - 1)
                val b = (note.endSec / frameSec).toInt().coerceIn(a, n - 1)
                for (f in a..b) {
                    val fs = f * frameSec; val fe = fs + frameSec
                    val ov = minOf(fe, note.endSec) - maxOf(fs, note.startSec)
                    if (ov > 0) fr[f][note.pitch % 12] += ov * (note.velocity / 127.0) * (if (note.pitch < 48) 1.3 else 1.0)
                }
            }
            return Chromagram(fr, frameSec)
        }

        /** Da audio: magnitudine STFT proiettata sulle classi di altezza (50 Hz – 5 kHz). */
        fun fromAudio(x: FloatArray, sr: Int, nFft: Int = 4096, hop: Int = 2048): Chromagram {
            val spec = Stft(nFft, hop).forward(x)
            val pcOfBin = IntArray(spec.nBins) { b ->
                val f = b.toDouble() * sr / nFft
                if (f < 50 || f > 5000) -1 else (Math.round(69 + 12 * ln(f / 440.0) / ln(2.0)).toInt() % 12 + 12) % 12
            }
            val fr = Array(spec.nFrames) { f ->
                val v = DoubleArray(12)
                for (b in 0 until spec.nBins) { val pc = pcOfBin[b]; if (pc >= 0) v[pc] += spec.mag[f][b].toDouble().let { it * it } }
                v
            }
            return Chromagram(fr, hop.toDouble() / sr)
        }
    }

    fun sum(from: Int = 0, to: Int = frames.size): DoubleArray {
        val s = DoubleArray(12)
        for (f in from until to.coerceAtMost(frames.size)) for (i in 0 until 12) s[i] += frames[f][i]
        return s
    }
}

data class KeyEstimate(val key: KeySignature, val score: Double, val runnerUp: KeySignature, val margin: Double)

/** Riconoscimento della tonalità con profili di Krumhansl–Kessler (correlazione di Pearson). */
object KeyDetector {
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    private fun pearson(a: DoubleArray, b: DoubleArray, rot: Int): Double {
        val ma = a.average(); val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until 12) {
            val x = a[(i + rot) % 12] - ma; val y = b[i] - mb
            num += x * y; da += x * x; db += y * y
        }
        return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
    }

    fun detect(pcHistogram: DoubleArray): KeyEstimate {
        val scored = (0 until 12).flatMap { t -> listOf(KeySignature(t, false) to pearson(pcHistogram, MAJOR, t), KeySignature(t, true) to pearson(pcHistogram, MINOR, t)) }
            .sortedByDescending { it.second }
        return KeyEstimate(scored[0].first, scored[0].second, scored[1].first, scored[0].second - scored[1].second)
    }

    fun detect(notes: List<NoteEvent>): KeyEstimate {
        val h = DoubleArray(12)
        for (n in notes) h[n.pitch % 12] += n.durationSec * (n.velocity / 127.0)
        return detect(h)
    }

    /**
     * Cambi di tonalità: stima per finestre di [windowSec], accettando un cambio solo se persiste
     * per [minPersist] finestre consecutive e con margine sufficiente (isteresi).
     */
    fun keyChanges(chroma: Chromagram, windowSec: Double = 16.0, hopSec: Double = 4.0, minPersist: Int = 3): List<Pair<Double, KeySignature>> {
        val w = max(1, (windowSec / chroma.frameSec).toInt()); val h = max(1, (hopSec / chroma.frameSec).toInt())
        val global = detect(chroma.sum()).key
        val local = ArrayList<Pair<Double, KeySignature>>()
        var s = 0
        while (s < chroma.frames.size) {
            val est = detect(chroma.sum(s, s + w))
            local.add(s * chroma.frameSec to if (est.margin < 0.02) global else est.key)
            s += h
        }
        val out = arrayListOf(0.0 to (local.firstOrNull()?.second ?: global))
        var i = 0
        while (i < local.size) {
            val k = local[i].second
            if (k != out.last().second) {
                var j = i
                while (j < local.size && local[j].second == k) j++
                if (j - i >= minPersist) out.add(local[i].first to k)
                i = j
            } else i++
        }
        return out
    }
}

data class ChordSegment(val startSec: Double, val endSec: Double, val chord: Chord?, val confidence: Double)

/** Riconoscimento accordi per template su finestre (tipicamente beat o mezze battute). */
object ChordRecognizer {
    private val TEMPLATES: List<Chord> = (0 until 12).flatMap { r ->
        listOf(ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DOM7, ChordQuality.MAJ7, ChordQuality.MIN7, ChordQuality.DIM, ChordQuality.SUS4, ChordQuality.AUG).map { Chord(r, it) }
    }

    /** Valuta un vettore croma, opzionalmente con la classe del basso. Ritorna null se troppo debole. */
    fun classify(chroma: DoubleArray, bassPc: Int? = null): Pair<Chord, Double>? {
        val total = chroma.sum()
        if (total <= 1e-9) return null
        val norm = DoubleArray(12) { chroma[it] / total }
        var best: Chord? = null; var bestScore = -1e9
        for (t in TEMPLATES) {
            val pcs = t.pitchClasses.toSet()
            var inside = 0.0; var outside = 0.0
            for (i in 0 until 12) if (i in pcs) inside += norm[i] else outside += norm[i]
            var score = inside - 1.2 * outside - 0.04 * (pcs.size - 3) // preferisci triadi a parità
            if (bassPc != null && bassPc == t.root) score += 0.15
            if (norm[t.root] < 0.05) score -= 0.2
            if (score > bestScore) { bestScore = score; best = t }
        }
        val b = best ?: return null
        val chord = if (bassPc != null && bassPc != b.root && bassPc in b.pitchClasses) b.copy(bass = bassPc) else b
        return chord to bestScore.coerceIn(0.0, 1.0)
    }

    fun recognize(notes: List<NoteEvent>, boundaries: List<Double>): List<ChordSegment> {
        val out = ArrayList<ChordSegment>()
        for (i in 0 until boundaries.size - 1) {
            val s = boundaries[i]; val e = boundaries[i + 1]
            val c = DoubleArray(12); var bass: Int? = null; var bassPitch = 999
            for (n in notes) {
                val ov = minOf(e, n.endSec) - maxOf(s, n.startSec)
                if (ov <= 0) continue
                c[n.pitch % 12] += ov * n.velocity
                if (n.pitch < bassPitch) { bassPitch = n.pitch; bass = n.pitch % 12 }
            }
            val r = classify(c, bass)
            val seg = ChordSegment(s, e, r?.first, r?.second ?: 0.0)
            val last = out.lastOrNull()
            if (last != null && last.chord == seg.chord) out[out.size - 1] = last.copy(endSec = e, confidence = (last.confidence + seg.confidence) / 2)
            else out.add(seg)
        }
        return out
    }
}

/**
 * Analisi strutturale: matrice di auto-similarità su cromagramma sincronizzato con le battute,
 * novità di Foote con kernel a scacchiera, raggruppamento delle sezioni simili (A, B, C...).
 */
object StructureAnalyzer {
    fun analyze(barChroma: List<DoubleArray>, barTimes: List<Double>, kernelBars: Int = 4, minSectionBars: Int = 4): List<Section> {
        val n = barChroma.size
        if (n < minSectionBars * 2) return if (n == 0) emptyList() else listOf(Section("A", barTimes.first(), barTimes.last()))
        val feats = barChroma.map { v -> val nr = sqrt(v.sumOf { it * it }).coerceAtLeast(1e-9); DoubleArray(12) { v[it] / nr } }
        fun sim(a: Int, b: Int): Double { var s = 0.0; for (i in 0 until 12) s += feats[a][i] * feats[b][i]; return s }
        val k = kernelBars
        val novelty = DoubleArray(n)
        for (c in k until n - k) {
            var v = 0.0
            for (i in -k until k) for (j in -k until k) {
                val sign = if ((i < 0) == (j < 0)) 1.0 else -1.0
                v += sign * sim(c + i, c + j)
            }
            novelty[c] = v
        }
        // picchi di novità distanziati almeno minSectionBars
        val cands = (1 until n - 1).filter { novelty[it] > 0 && novelty[it] >= novelty[it - 1] && novelty[it] >= novelty[it + 1] }
            .sortedByDescending { novelty[it] }
        val bounds = sortedSetOf(0, n)
        val thr = (novelty.maxOrNull() ?: 0.0) * 0.25
        for (c in cands) if (novelty[c] >= thr && bounds.all { kotlin.math.abs(it - c) >= minSectionBars }) bounds.add(c)
        val b = bounds.toList()
        // etichettatura: confronto del profilo medio di ciascuna sezione
        val profiles = (0 until b.size - 1).map { s -> DoubleArray(12) { i -> (b[s] until b[s + 1]).sumOf { feats[it][i] } / (b[s + 1] - b[s]) } }
        val labels = ArrayList<Char>(); val reps = ArrayList<DoubleArray>()
        for (p in profiles) {
            val np = sqrt(p.sumOf { it * it }).coerceAtLeast(1e-9)
            var found = -1
            for ((ri, r) in reps.withIndex()) {
                val nr = sqrt(r.sumOf { it * it }).coerceAtLeast(1e-9)
                var d = 0.0; for (i in 0 until 12) d += p[i] * r[i]
                if (d / (np * nr) > 0.93) { found = ri; break }
            }
            if (found < 0) { reps.add(p); labels.add('A' + reps.size - 1) } else labels.add('A' + found)
        }
        val endTime = barTimes.getOrElse(n) { barTimes.last() + (barTimes.last() - barTimes[n - 2]) }
        return (0 until b.size - 1).map { s -> Section(labels[s].toString(), barTimes[b[s]], if (b[s + 1] < n) barTimes[b[s + 1]] else endTime) }
    }
}
