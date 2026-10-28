package org.ampy.rhythmlab

import org.ampy.audioengine.Fft
import org.ampy.audioengine.Windows
import org.ampy.core.ModuleInfo
import org.ampy.core.NoteEvent
import org.ampy.core.TempoChange
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object RhythmLabModule {
    val info = ModuleInfo("AmpyRhythmLab", "0.1.0", "Onset, tempo e variazioni BPM, beat tracking, quantizzazione, batteria")
}

/** Inviluppo di novità (spectral flux con compressione logaritmica) a [fps] frame/s. */
class OnsetEnvelope(val values: FloatArray, val fps: Double) {
    companion object {
        fun compute(x: FloatArray, sr: Int, nFft: Int = 2048, hop: Int = 512, bandHz: ClosedFloatingPointRange<Double>? = null): OnsetEnvelope {
            val fft = Fft(nFft); val win = Windows.hann(nFft)
            val nFrames = max(0, (x.size - nFft) / hop + 1)
            val nBins = nFft / 2 + 1
            val lo = bandHz?.let { (it.start / sr * nFft).toInt().coerceIn(0, nBins - 1) } ?: 0
            val hi = bandHz?.let { (it.endInclusive / sr * nFft).toInt().coerceIn(lo + 1, nBins) } ?: nBins
            var prev = FloatArray(nBins)
            val env = FloatArray(nFrames)
            val re = DoubleArray(nFft); val im = DoubleArray(nFft)
            for (f in 0 until nFrames) {
                for (i in 0 until nFft) { re[i] = x[f * hop + i] * win[i]; im[i] = 0.0 }
                fft.transform(re, im)
                val cur = FloatArray(nBins)
                var flux = 0.0
                for (b in lo until hi) {
                    cur[b] = ln(1 + 100 * sqrt(re[b] * re[b] + im[b] * im[b])).toFloat()
                    val d = cur[b] - prev[b]
                    if (d > 0) flux += d
                }
                env[f] = flux.toFloat(); prev = cur
            }
            return OnsetEnvelope(env, sr.toDouble() / hop)
        }
    }

    /** Picchi con soglia adattiva (media mobile + delta), restituiti in secondi. */
    fun peaks(delta: Double = 0.07, window: Int = 8, minGapSec: Double = 0.05): List<Double> {
        if (values.isEmpty()) return emptyList()
        val mx = values.max().coerceAtLeast(1e-9f)
        val v = FloatArray(values.size) { values[it] / mx }
        val out = ArrayList<Double>()
        var last = -1e9
        for (i in 1 until v.size - 1) {
            if (v[i] < v[i - 1] || v[i] < v[i + 1]) continue
            var mean = 0.0; var c = 0
            for (j in maxOf(0, i - window)..minOf(v.size - 1, i + window)) { mean += v[j]; c++ }
            mean /= c
            val t = i / fps
            if (v[i] >= mean + delta && t - last >= minGapSec) { out.add(t); last = t }
        }
        return out
    }
}

data class TempoEstimate(val bpm: Double, val confidence: Double)

object TempoEstimator {
    /**
     * Stima del tempo globale: autocorrelazione dell'inviluppo di onset pesata da un prior
     * log-gaussiano centrato su [priorBpm] (come librosa.beat.tempo).
     */
    fun estimate(env: OnsetEnvelope, minBpm: Double = 50.0, maxBpm: Double = 220.0, priorBpm: Double = 120.0, priorStdOct: Double = 1.0): TempoEstimate {
        val v = env.values
        if (v.size < 8) return TempoEstimate(priorBpm, 0.0)
        val mean = v.average()
        val x = DoubleArray(v.size) { v[it] - mean }
        val minLag = (60.0 / maxBpm * env.fps).toInt().coerceAtLeast(1)
        val maxLag = (60.0 / minBpm * env.fps).toInt().coerceAtMost(x.size - 1)
        var best = -1; var bestScore = -1e18; var total = 0.0
        val scores = DoubleArray(maxLag + 1)
        val zero = x.sumOf { it * it }.coerceAtLeast(1e-12)
        for (lag in minLag..maxLag) {
            var ac = 0.0
            for (i in lag until x.size) ac += x[i] * x[i - lag]
            ac /= zero
            val bpm = 60.0 * env.fps / lag
            val w = exp(-0.5 * (log2(bpm / priorBpm) / priorStdOct).let { it * it })
            scores[lag] = ac * w
            if (scores[lag] > 0) total += scores[lag]
            if (scores[lag] > bestScore) { bestScore = scores[lag]; best = lag }
        }
        if (best < 0) return TempoEstimate(priorBpm, 0.0)
        // raffinamento parabolico del lag
        var lagF = best.toDouble()
        if (best in (minLag + 1) until maxLag) {
            val a = scores[best - 1]; val b = scores[best]; val c = scores[best + 1]
            val den = a - 2 * b + c
            if (abs(den) > 1e-12) lagF = best + 0.5 * (a - c) / den
        }
        val conf = if (total > 0) (bestScore.coerceAtLeast(0.0) / total * 10).coerceIn(0.0, 1.0) else 0.0
        return TempoEstimate(60.0 * env.fps / lagF, conf)
    }

    /**
     * Variazioni di BPM: stima su finestre scorrevoli; i cambi sono registrati solo se
     * superano [changeThresholdPct] e persistono per almeno due finestre.
     */
    fun tempoCurve(env: OnsetEnvelope, windowSec: Double = 12.0, hopSec: Double = 4.0, changeThresholdPct: Double = 4.0): List<TempoChange> {
        val global = estimate(env)
        val wl = (windowSec * env.fps).toInt(); val hl = (hopSec * env.fps).toInt().coerceAtLeast(1)
        if (env.values.size < wl * 2) return listOf(TempoChange(0.0, round2(global.bpm)))
        val local = ArrayList<Pair<Double, Double>>()
        var s = 0
        while (s + wl <= env.values.size) {
            val sub = OnsetEnvelope(env.values.copyOfRange(s, s + wl), env.fps)
            val e = estimate(sub, priorBpm = global.bpm, priorStdOct = 0.6)
            local.add(s / env.fps to foldToward(e.bpm, global.bpm))
            s += hl
        }
        val out = ArrayList<TempoChange>()
        out.add(TempoChange(0.0, round2(local.first().second)))
        for (i in 1 until local.size - 1) {
            val cur = out.last().bpm
            val a = local[i].second; val b = local[i + 1].second
            if (abs(a - cur) / cur * 100 > changeThresholdPct && abs(b - a) / a * 100 < changeThresholdPct) {
                out.add(TempoChange(local[i].first + windowSec / 2 - hopSec / 2, round2((a + b) / 2)))
            }
        }
        return out
    }

    /** Riporta errori d'ottava (x2, /2) vicino al tempo di riferimento. */
    fun foldToward(bpm: Double, ref: Double): Double {
        val cands = listOf(bpm, bpm * 2, bpm / 2)
        return cands.minBy { abs(log2(it / ref)) }
    }

    private fun round2(x: Double) = (x * 100).roundToInt() / 100.0
}

/** Beat tracking a programmazione dinamica (Ellis 2007). */
object BeatTracker {
    fun track(env: OnsetEnvelope, bpm: Double, tightness: Double = 100.0): List<Double> {
        val v = env.values
        if (v.isEmpty()) return emptyList()
        val period = 60.0 * env.fps / bpm
        val std = v.map { it.toDouble() }.let { l -> val m = l.average(); sqrt(l.sumOf { (it - m) * (it - m) } / l.size) }.coerceAtLeast(1e-9)
        val local = DoubleArray(v.size) { v[it] / std }
        val score = DoubleArray(v.size); val back = IntArray(v.size) { -1 }
        val lo = (period / 2).roundToInt().coerceAtLeast(1); val hi = (period * 2).roundToInt()
        for (i in v.indices) {
            var best = 0.0; var bi = -1
            for (d in lo..hi) {
                val j = i - d
                if (j < 0) break
                val pen = -tightness * ln(d / period).let { it * it }
                val s = score[j] + pen
                if (bi < 0 || s > best) { best = s; bi = j }
            }
            score[i] = local[i] + (if (bi >= 0) best else 0.0)
            back[i] = bi
        }
        // punto finale: massimo tra gli ultimi frame con score "locale" significativo
        val tailStart = maxOf(0, v.size - period.roundToInt() * 2)
        var end = tailStart
        for (i in tailStart until v.size) if (score[i] > score[end]) end = i
        val beats = ArrayList<Double>()
        var i = end
        while (i >= 0) { beats.add(i / env.fps); i = back[i] }
        return beats.reversed()
    }
}

/** Mappa di tempo a tratti costanti: conversione secondi <-> beat. */
class TempoMap(changes: List<TempoChange>) {
    val changes = changes.sortedBy { it.timeSec }.ifEmpty { listOf(TempoChange(0.0, 120.0)) }
    private val beatAt = DoubleArray(this.changes.size).also { arr ->
        for (i in 1 until arr.size) arr[i] = arr[i - 1] + (this.changes[i].timeSec - this.changes[i - 1].timeSec) * this.changes[i - 1].bpm / 60.0
    }

    fun secToBeat(t: Double): Double {
        var i = changes.indexOfLast { it.timeSec <= t }.coerceAtLeast(0)
        return beatAt[i] + (t - changes[i].timeSec) * changes[i].bpm / 60.0
    }

    fun beatToSec(b: Double): Double {
        var i = beatAt.indexOfLast { it <= b }.coerceAtLeast(0)
        return changes[i].timeSec + (b - beatAt[i]) * 60.0 / changes[i].bpm
    }
}

/** Nota quantizzata in tick (divisions per quarto). */
data class QuantizedNote(val note: NoteEvent, val startTick: Long, val durTicks: Long)

object Quantizer {
    /** Suddivisioni candidate per quarto: 1=semiminima, 2=crome, 3=terzine, 4=semicrome, 6=sestine. */
    val CANDIDATE_SUBDIVISIONS = intArrayOf(1, 2, 3, 4, 6)

    /**
     * Sceglie la suddivisione che minimizza l'errore medio di onset penalizzando la complessità,
     * restituendo la suddivisione per quarto.
     */
    fun detectSubdivision(onsetsBeats: List<Double>, maxSubdiv: Int = 4): Int {
        if (onsetsBeats.isEmpty()) return 2
        var best = 1; var bestCost = Double.MAX_VALUE
        for (s in CANDIDATE_SUBDIVISIONS) {
            if (s > maxSubdiv && s != 3) continue
            var err = 0.0
            for (b in onsetsBeats) { val q = Math.round(b * s) / s.toDouble(); err += abs(b - q) }
            val cost = err / onsetsBeats.size + 0.012 * s // penalità di complessità
            if (cost < bestCost) { bestCost = cost; best = s }
        }
        return best
    }

    /**
     * Quantizza le note su una griglia di [subdiv] per quarto, espressa in [divisions] tick per quarto.
     * Le durate minime sono 1 passo di griglia.
     */
    fun quantize(notes: List<NoteEvent>, tempo: TempoMap, subdiv: Int, divisions: Int): List<QuantizedNote> {
        require(divisions % subdiv == 0) { "divisions deve essere multiplo di subdiv" }
        val step = (divisions / subdiv).toLong()
        return notes.map { n ->
            val sb = tempo.secToBeat(n.startSec); val eb = tempo.secToBeat(n.endSec)
            val st = Math.round(sb * subdiv) * step
            var et = Math.round(eb * subdiv) * step
            if (et <= st) et = st + step
            QuantizedNote(n, st, et - st)
        }.sortedWith(compareBy({ it.startTick }, { it.note.pitch }))
    }
}

/** Trascrizione percussiva euristica: onset per banda -> cassa (36), rullante (38), charleston (42). */
object DrumTranscriber {
    data class Band(val pitch: Int, val range: ClosedFloatingPointRange<Double>, val delta: Double)
    val BANDS = listOf(Band(36, 30.0..150.0, 0.12), Band(38, 180.0..2500.0, 0.15), Band(42, 6000.0..14000.0, 0.12))

    fun transcribe(x: FloatArray, sr: Int): List<NoteEvent> {
        val out = ArrayList<NoteEvent>()
        for (b in BANDS) {
            if (b.range.start >= sr / 2.0) continue
            val env = OnsetEnvelope.compute(x, sr, 1024, 256, b.range.start..minOf(b.range.endInclusive, sr / 2.0 - 1))
            val mx = env.values.maxOrNull()?.coerceAtLeast(1e-9f) ?: continue
            for (t in env.peaks(delta = b.delta, minGapSec = 0.06)) {
                val idx = (t * env.fps).toInt().coerceIn(0, env.values.size - 1)
                val strength = (env.values[idx] / mx).coerceIn(0f, 1f)
                out.add(NoteEvent(b.pitch, t, t + 0.1, (40 + 87 * strength).toInt().coerceIn(1, 127), confidence = 0.5f * strength + 0.2f))
            }
        }
        return out.sortedBy { it.startSec }
    }
}
