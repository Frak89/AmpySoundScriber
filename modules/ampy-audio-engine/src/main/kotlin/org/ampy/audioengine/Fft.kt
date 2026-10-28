package org.ampy.audioengine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT complessa radix-2 in-place con tabelle precalcolate (riutilizzabile, non thread-safe). */
class Fft(val n: Int) {
    init { require(n >= 2 && (n and (n - 1)) == 0) { "n deve essere potenza di 2, era $n" } }
    private val cosT = DoubleArray(n / 2) { cos(-2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(-2 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Trasformata diretta (inverse=false) o inversa normalizzata 1/n (inverse=true). */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) -sinT[k] else sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}

object Windows {
    /** Finestra di Hann periodica (come scipy/librosa per STFT). */
    fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
    fun gaussian(m: Int, std: Double): DoubleArray {
        val c = (m - 1) / 2.0
        return DoubleArray(m) { val x = (it - c) / std; kotlin.math.exp(-0.5 * x * x) }
    }
}

fun nextPow2(x: Int): Int { var p = 1; while (p < x) p = p shl 1; return p }
