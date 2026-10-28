package org.ampy.audioengine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Spettro complesso: frames x bins (bins = nFft/2+1), memorizzato come magnitudine e fase
 * per facilitare mascheramento e ricostruzione.
 */
class Spectrum(val nFrames: Int, val nBins: Int, val mag: Array<FloatArray>, val phase: Array<FloatArray>)

/**
 * STFT con finestra di Hann e padding centrato (come librosa center=True, pad riflesso -> qui zero-pad),
 * più iSTFT con overlap-add normalizzato (ricostruzione perfetta per hop <= nFft/2).
 */
class Stft(val nFft: Int = 2048, val hop: Int = 512) {
    private val fft = Fft(nFft)
    private val window = Windows.hann(nFft)
    val nBins = nFft / 2 + 1

    fun frameCount(nSamples: Int): Int = 1 + nSamples / hop

    fun forward(x: FloatArray): Spectrum {
        val nFrames = frameCount(x.size)
        val mag = Array(nFrames) { FloatArray(nBins) }
        val ph = Array(nFrames) { FloatArray(nBins) }
        val re = DoubleArray(nFft); val im = DoubleArray(nFft)
        val pad = nFft / 2
        for (f in 0 until nFrames) {
            val start = f * hop - pad
            for (i in 0 until nFft) {
                val idx = start + i
                re[i] = if (idx in x.indices) x[idx] * window[i] else 0.0
                im[i] = 0.0
            }
            fft.transform(re, im)
            for (b in 0 until nBins) {
                mag[f][b] = hypot(re[b], im[b]).toFloat()
                ph[f][b] = atan2(im[b], re[b]).toFloat()
            }
        }
        return Spectrum(nFrames, nBins, mag, ph)
    }

    /** Ricostruisce un segnale di [length] campioni da magnitudine (eventualmente mascherata) e fase. */
    fun inverse(mag: Array<FloatArray>, phase: Array<FloatArray>, length: Int): FloatArray {
        val out = DoubleArray(length + nFft)
        val norm = DoubleArray(length + nFft)
        val re = DoubleArray(nFft); val im = DoubleArray(nFft)
        val pad = nFft / 2
        for (f in mag.indices) {
            for (b in 0 until nBins) {
                re[b] = mag[f][b] * cos(phase[f][b].toDouble())
                im[b] = mag[f][b] * sin(phase[f][b].toDouble())
            }
            for (b in 1 until nFft / 2) { re[nFft - b] = re[b]; im[nFft - b] = -im[b] }
            fft.transform(re, im, inverse = true)
            val start = f * hop
            for (i in 0 until nFft) {
                val o = start + i
                if (o < out.size) { out[o] += re[i] * window[i]; norm[o] += window[i] * window[i] }
            }
        }
        return FloatArray(length) { i ->
            val o = i + pad
            if (norm[o] > 1e-8) (out[o] / norm[o]).toFloat() else 0f
        }
    }
}

/** Spettrogramma in dB per la visualizzazione (frames x bins ridotti), normalizzato 0..1. */
object SpectrogramRenderer {
    fun render(x: FloatArray, sampleRate: Int, nFft: Int = 2048, hop: Int = 512, maxBins: Int = 256, maxHz: Double = 8000.0, floorDb: Double = -80.0): Array<FloatArray> {
        val spec = Stft(nFft, hop).forward(x)
        val usefulBins = ((maxHz / (sampleRate / 2.0)) * (spec.nBins - 1)).toInt().coerceIn(2, spec.nBins)
        val outBins = minOf(maxBins, usefulBins)
        var maxMag = 1e-12
        for (row in spec.mag) for (v in row) if (v > maxMag) maxMag = v.toDouble()
        return Array(spec.nFrames) { f ->
            FloatArray(outBins) { ob ->
                // mappatura logaritmica in frequenza: più dettaglio sulle basse
                val frac = ob.toDouble() / (outBins - 1)
                val bin = ((Math.pow(usefulBins.toDouble(), frac)) - 1).toInt().coerceIn(0, usefulBins - 1)
                val db = 20 * kotlin.math.log10(spec.mag[f][bin] / maxMag + 1e-12)
                ((db - floorDb) / -floorDb).coerceIn(0.0, 1.0).toFloat()
            }
        }
    }
}
