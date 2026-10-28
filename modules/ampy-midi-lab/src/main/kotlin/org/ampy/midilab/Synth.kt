package org.ampy.midilab

import org.ampy.audioengine.AudioBuffer
import org.ampy.core.CancellationToken
import org.ampy.core.InstrumentKind
import org.ampy.core.NoteEvent
import org.ampy.core.Track
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Sintetizzatore offline di anteprima (nessun SoundFont necessario, quindi 100% offline):
 * timbri additivi per famiglia di strumento e batteria sintetica. Serve a "ricostruire" il suono
 * di una trascrizione e a esportare WAV; non pretende realismo da campionatore.
 */
object PreviewSynth {
    private data class Timbre(val harmonics: DoubleArray, val attack: Double, val decay: Double, val sustain: Double, val release: Double, val brightDecay: Double)

    private fun timbre(k: InstrumentKind): Timbre = when (k) {
        InstrumentKind.PIANO, InstrumentKind.ELECTRIC_PIANO -> Timbre(doubleArrayOf(1.0, 0.5, 0.3, 0.2, 0.1, 0.05), 0.005, 0.8, 0.25, 0.25, 3.0)
        InstrumentKind.ORGAN -> Timbre(doubleArrayOf(1.0, 0.8, 0.6, 0.4, 0.3, 0.2), 0.01, 0.05, 0.9, 0.08, 0.0)
        InstrumentKind.GUITAR, InstrumentKind.ELECTRIC_GUITAR -> Timbre(doubleArrayOf(1.0, 0.6, 0.45, 0.3, 0.2, 0.15, 0.1), 0.003, 0.6, 0.15, 0.15, 5.0)
        InstrumentKind.BASS -> Timbre(doubleArrayOf(1.0, 0.5, 0.25, 0.1), 0.005, 0.4, 0.4, 0.08, 2.0)
        InstrumentKind.STRINGS -> Timbre(doubleArrayOf(1.0, 0.6, 0.5, 0.35, 0.25, 0.2), 0.12, 0.2, 0.85, 0.3, 0.0)
        InstrumentKind.BRASS -> Timbre(doubleArrayOf(1.0, 0.9, 0.7, 0.5, 0.35, 0.2), 0.05, 0.1, 0.8, 0.12, 0.0)
        InstrumentKind.WOODWIND, InstrumentKind.VOICE -> Timbre(doubleArrayOf(1.0, 0.3, 0.15, 0.05), 0.06, 0.1, 0.85, 0.12, 0.0)
        InstrumentKind.SYNTH_LEAD -> Timbre(DoubleArray(10) { 1.0 / (it + 1) }, 0.01, 0.1, 0.8, 0.1, 0.0)
        InstrumentKind.SYNTH_PAD -> Timbre(doubleArrayOf(1.0, 0.4, 0.2, 0.1), 0.4, 0.3, 0.8, 0.6, 0.0)
        else -> Timbre(doubleArrayOf(1.0, 0.4, 0.2), 0.01, 0.3, 0.6, 0.1, 1.0)
    }

    fun midiToHz(p: Double) = 440.0 * 2.0.pow((p - 69) / 12)

    fun renderTrack(track: Track, sr: Int, durationSec: Double, cancel: CancellationToken = CancellationToken.NONE): FloatArray =
        renderRange(track, sr, 0L, (durationSec * sr).toInt() + sr, cancel)

    /**
     * Rende solo i campioni [from, from+count): permette di sintetizzare brani lunghi a blocchi
     * con memoria costante. Il risultato coincide con il rendering completo nello stesso intervallo.
     */
    fun renderRange(track: Track, sr: Int, from: Long, count: Int, cancel: CancellationToken = CancellationToken.NONE): FloatArray {
        val out = FloatArray(count)
        val tb = timbre(track.instrument)
        val maxTail = if (track.instrument.isDrum) 1.0 else tb.release
        val rangeStartSec = from.toDouble() / sr; val rangeEndSec = (from + count).toDouble() / sr
        for ((i, n) in track.notes.withIndex()) {
            if (i % 64 == 0) cancel.throwIfCancelled()
            if (n.startSec >= rangeEndSec || n.endSec + maxTail < rangeStartSec) continue
            if (track.instrument.isDrum) { drum(n, out, sr, from); continue }
            val f0 = midiToHz(n.pitch.toDouble())
            val amp = (n.velocity / 127.0) * 0.25
            val start = (n.startSec * sr).toLong()
            val len = ((n.durationSec + tb.release) * sr).toInt()
            val bends = n.pitchBends
            var phase = DoubleArray(tb.harmonics.size)
            for (s in 0 until len) {
                val idxL = start + s - from
                if (idxL >= out.size) break
                val idx = idxL.toInt()
                val t = s.toDouble() / sr
                val env = adsr(t, n.durationSec, tb)
                if (env <= 0.0) continue
                val bend = if (bends.isNullOrEmpty() || n.durationSec <= 0) 0.0 else {
                    val pos = (t / n.durationSec * (bends.size - 1)).coerceIn(0.0, bends.size - 1.0)
                    bends[pos.toInt()] / 3.0
                }
                val f = f0 * 2.0.pow(bend / 12)
                var v = 0.0
                for (h in tb.harmonics.indices) {
                    val fh = f * (h + 1)
                    if (fh >= sr / 2.0) break
                    phase[h] += 2 * PI * fh / sr
                    val hAmp = tb.harmonics[h] * (if (tb.brightDecay > 0) exp(-t * tb.brightDecay * h * 0.5) else 1.0)
                    v += hAmp * sin(phase[h])
                }
                if (idx >= 0) out[idx] += (v * env * amp).toFloat()
            }
        }
        return out
    }

    private fun adsr(t: Double, dur: Double, tb: Timbre): Double {
        fun held(tt: Double): Double = when {
            tt < tb.attack -> tt / tb.attack
            tt < tb.attack + tb.decay -> 1 - (1 - tb.sustain) * (tt - tb.attack) / tb.decay
            else -> tb.sustain
        }
        return if (t <= dur) held(t) else { val r = held(dur) * (1 - (t - dur) / tb.release); if (r < 0) 0.0 else r }
    }

    private fun drum(n: NoteEvent, out: FloatArray, sr: Int, from: Long = 0L) {
        val startAbs = (n.startSec * sr).toLong(); val start = (startAbs - from).toInt(); val amp = n.velocity / 127.0 * 0.5
        val rnd = java.util.Random(n.pitch * 31L + startAbs)
        val (len, kind) = when (n.pitch) { 35, 36 -> 0.35 to 0; 38, 40, 37 -> 0.2 to 1; 42, 44 -> 0.06 to 2; 46 -> 0.25 to 2; 49, 57, 51 -> 0.8 to 3; else -> 0.15 to 1 }
        var ph = 0.0
        for (s in 0 until (len * sr).toInt()) {
            val i = start + s; if (i >= out.size) break
            if (i < 0) { if (kind == 1 || kind >= 2) rnd.nextGaussian(); if (kind == 0 || kind == 1) ph += 2 * PI * (if (kind == 0) 50 + 100 * exp(-s.toDouble() / sr * 30) else 190.0) / sr; continue }
            val t = s.toDouble() / sr
            val v = when (kind) {
                0 -> { val f = 50 + 100 * exp(-t * 30); ph += 2 * PI * f / sr; sin(ph) * exp(-t * 9) }
                1 -> { ph += 2 * PI * 190 / sr; 0.5 * sin(ph) * exp(-t * 20) + 0.6 * rnd.nextGaussian() * exp(-t * 25) }
                2 -> rnd.nextGaussian() * exp(-t * 60) * 0.5
                else -> rnd.nextGaussian() * exp(-t * 4) * 0.35
            }
            out[i] += (v * amp).toFloat()
        }
    }

    /** Mixdown stereo di più tracce (rispettando mute/solo/volume/pan). */
    fun renderMix(tracks: List<Track>, sr: Int, durationSec: Double, cancel: CancellationToken = CancellationToken.NONE): AudioBuffer {
        val anySolo = tracks.any { it.solo }
        val n = (durationSec * sr).toInt() + sr
        val l = FloatArray(n); val r = FloatArray(n)
        for (t in tracks) {
            if (t.muted || (anySolo && !t.solo)) continue
            val mono = renderTrack(t, sr, durationSec, cancel)
            val angle = (t.pan.coerceIn(-1f, 1f) + 1) * PI / 4
            val gl = kotlin.math.cos(angle).toFloat() * t.volume * 1.414f; val gr = sin(angle).toFloat() * t.volume * 1.414f
            for (i in 0 until minOf(n, mono.size)) { l[i] += mono[i] * gl; r[i] += mono[i] * gr }
        }
        // limitatore morbido
        for (i in 0 until n) { l[i] = kotlin.math.tanh(l[i]); r[i] = kotlin.math.tanh(r[i]) }
        return AudioBuffer(sr, arrayOf(l, r))
    }
}
