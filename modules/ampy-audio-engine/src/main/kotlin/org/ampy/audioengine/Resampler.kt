package org.ampy.audioengine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ricampionatore a banda limitata (sinc finestrata Kaiser) con rapporto arbitrario.
 * Qualità: ~ -90 dB di reiezione con [halfTaps]=32, beta=9. Funziona in streaming tramite [processBlock]
 * mantenendo uno storico interno, così i file lunghi non devono stare interamente in memoria.
 */
class Resampler(val srcRate: Int, val dstRate: Int, private val halfTaps: Int = 32, beta: Double = 9.0) {
    private val ratio = dstRate.toDouble() / srcRate
    private val cutoff = minOf(1.0, ratio) * 0.97
    private val tableRes = 512
    private val table: DoubleArray
    init {
        val len = halfTaps * tableRes + 1
        val i0b = besselI0(beta)
        table = DoubleArray(len) { i ->
            val x = i.toDouble() / tableRes
            val s = if (x == 0.0) 1.0 else sin(PI * x * cutoff) / (PI * x * cutoff)
            val w = besselI0(beta * sqrt(maxOf(0.0, 1 - (x / halfTaps) * (x / halfTaps)))) / i0b
            s * w * cutoff
        }
    }

    private fun kernel(x: Double): Double {
        val ax = abs(x)
        if (ax >= halfTaps) return 0.0
        val p = ax * tableRes
        val i = p.toInt()
        val f = p - i
        return table[i] * (1 - f) + table[minOf(i + 1, table.size - 1)] * f
    }

    /** Ricampiona un intero segnale (uso semplice). */
    fun process(x: FloatArray): FloatArray {
        if (srcRate == dstRate) return x.copyOf()
        val outLen = floor(x.size * ratio).toInt()
        val y = FloatArray(outLen)
        for (n in 0 until outLen) {
            val t = n / ratio
            val c = floor(t).toInt()
            var acc = 0.0
            for (k in c - halfTaps + 1..c + halfTaps) {
                if (k < 0 || k >= x.size) continue
                acc += x[k] * kernel(t - k)
            }
            y[n] = acc.toFloat()
        }
        return y
    }

    // ---- streaming ----
    private var history = FloatArray(0)
    private var historyStart = 0L     // indice assoluto del primo campione in history
    private var nextOut = 0L          // prossimo indice di uscita assoluto
    private var totalIn = 0L

    /** Fornisce un blocco di ingresso; restituisce i campioni di uscita calcolabili. [last]=true svuota la coda. */
    fun processBlock(block: FloatArray, last: Boolean = false): FloatArray {
        if (srcRate == dstRate) return block.copyOf()
        val merged = FloatArray(history.size + block.size)
        history.copyInto(merged); block.copyInto(merged, history.size)
        totalIn += block.size
        val out = ArrayList<Float>()
        while (true) {
            val t = nextOut / ratio
            val c = floor(t).toLong()
            val needUpTo = c + halfTaps
            if (!last && needUpTo >= totalIn) break
            if (last && t >= totalIn) break
            var acc = 0.0
            for (k in c - halfTaps + 1..c + halfTaps) {
                if (k < 0 || k >= totalIn) continue
                val idx = (k - historyStart).toInt()
                if (idx < 0 || idx >= merged.size) continue
                acc += merged[idx] * kernel(t - k)
            }
            out.add(acc.toFloat())
            nextOut++
        }
        // conserva lo storico necessario per i prossimi campioni
        val keepFrom = maxOf(historyStart, floor(nextOut / ratio).toLong() - halfTaps)
        val drop = (keepFrom - historyStart).toInt().coerceIn(0, merged.size)
        history = merged.copyOfRange(drop, merged.size)
        historyStart += drop
        return out.toFloatArray()
    }

    companion object {
        fun besselI0(x: Double): Double {
            var sum = 1.0; var term = 1.0; var k = 1
            val hx = x / 2
            while (term > 1e-12 * sum) { term *= (hx / k) * (hx / k); sum += term; k++ }
            return sum
        }
    }
}
