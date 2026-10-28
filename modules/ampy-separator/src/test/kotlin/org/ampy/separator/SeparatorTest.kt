package org.ampy.separator

import org.ampy.audioengine.AudioBuffer
import org.ampy.audioengine.MemoryPcmSource
import org.ampy.audioengine.RawFloatFile
import org.ampy.core.FloatTensor
import org.ampy.core.InferenceDevice
import org.ampy.core.JobContext
import org.ampy.core.TensorRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

class SeparatorTest {
    private val sr = 22050
    private fun tmp() = kotlin.io.path.createTempDirectory("sep").toFile()
    private fun read(s: Stem) = RawFloatFile(s.file, s.sampleRate, s.channels).use { it.readMono(0, it.frameCount.toInt()) }
    private fun sdr(ref: FloatArray, est: FloatArray): Double {
        var num = 0.0; var den = 0.0
        for (i in ref.indices) { num += ref[i] * ref[i]; val d = ref[i] - est.getOrElse(i) { 0f }; den += d * d }
        return 10 * log10(num / (den + 1e-12))
    }

    private val tone = FloatArray(sr * 6) { (0.4 * sin(2 * PI * 440 * it / sr)).toFloat() }
    private val clicks = FloatArray(sr * 6).also { x ->
        val rnd = java.util.Random(5)
        var t = 0
        while (t < x.size) { for (i in 0 until 300) if (t + i < x.size) x[t + i] += (rnd.nextGaussian() * 0.5 * exp(-i / 40.0)).toFloat(); t += sr / 4 }
    }
    private val mix = FloatArray(tone.size) { tone[it] + clicks[it] }

    @Test fun hpssSplitsSustainedAndTransients() {
        val dir = tmp()
        // segmenti da 2 s per collaudare cucitura e dissolvenze
        val res = HpssSeparator(segmentSec = 2.0).separate(MemoryPcmSource(AudioBuffer.mono(sr, mix)), dir, JobContext())
        val h = read(res.stems.first { it.kind == StemKind.HARMONIC })
        val p = read(res.stems.first { it.kind == StemKind.PERCUSSIVE })
        assertEquals(mix.size, h.size); assertEquals(mix.size, p.size)
        // le maschere di Wiener sommano a 1: h + p ricostruisce la miscela
        val sum = FloatArray(mix.size) { h[it] + p[it] }
        assertTrue("ricostruzione SDR ${sdr(mix, sum)}", sdr(mix, sum) > 25)
        assertTrue("SDR armonico ${sdr(tone, h)}", sdr(tone, h) > 6)
        assertTrue("SDR percussivo ${sdr(clicks, p)}", sdr(clicks, p) > 2)
        assertTrue(res.report.warnings.any { it.contains("DSP") })
        dir.deleteRecursively()
    }

    /** Runner di prova: "stima" la sorgente 440 Hz tenendo solo i bin vicini a 440 Hz. */
    private class BandRunner(val sr: Int, val nFft: Int, val keepHz: ClosedFloatingPointRange<Double>) : TensorRunner {
        override val inputNames = listOf("mix"); override val outputNames = listOf("estimate"); override val activeDevice = InferenceDevice.CPU
        override fun run(inputs: Map<String, FloatTensor>): Map<String, FloatTensor> {
            val x = inputs["mix"]!!; val (_, ch, nB, nT) = x.shape.toList()
            val out = FloatArray(x.size)
            for (c in 0 until ch) for (b in 0 until nB) { val hz = b.toDouble() * sr / nFft; if (hz in keepHz) for (t in 0 until nT) out[(c * nB + b) * nT + t] = x.data[(c * nB + b) * nT + t] }
            return mapOf("estimate" to FloatTensor(x.shape, out))
        }
        override fun close() {}
    }

    @Test fun spectralMaskPipeline() {
        val other = FloatArray(tone.size) { (0.3 * sin(2 * PI * 1500 * it / sr)).toFloat() }
        val m = FloatArray(tone.size) { tone[it] + other[it] }
        val sep = SpectralMaskModelSeparator("test", "test", mapOf(StemKind.VOCALS to BandRunner(sr, 4096, 380.0..500.0)), segmentSec = 2.0)
        val dir = tmp()
        val res = sep.separate(MemoryPcmSource(AudioBuffer.mono(sr, m)), dir, JobContext())
        val v = read(res.stems.first { it.kind == StemKind.VOCALS }); val r = read(res.stems.first { it.kind == StemKind.RESIDUAL })
        assertTrue("SDR stem ${sdr(tone, v)}", sdr(tone, v) > 20)
        assertTrue("SDR residuo ${sdr(other, r)}", sdr(other, r) > 20)
        dir.deleteRecursively()
    }

    private class HalfRunner : TensorRunner {
        override val inputNames = listOf("mix"); override val outputNames = listOf("stems"); override val activeDevice = InferenceDevice.CPU
        override fun run(inputs: Map<String, FloatTensor>): Map<String, FloatTensor> {
            val x = inputs["mix"]!!; val n = x.shape[2]
            val out = FloatArray(2 * 2 * n)
            for (s in 0 until 2) for (c in 0 until 2) for (i in 0 until n) out[(s * 2 + c) * n + i] = x.data[c * n + i] * (if (s == 0) 0.25f else 0.75f)
            return mapOf("stems" to FloatTensor(intArrayOf(1, 2, 2, n), out))
        }
        override fun close() {}
    }

    @Test fun waveformModelStitching() {
        val stereo = AudioBuffer(sr, arrayOf(mix, tone))
        val sep = WaveformModelSeparator("w", "w", HalfRunner(), listOf(StemKind.DRUMS, StemKind.OTHER), modelRate = sr, segmentSamples = sr, overlapSamples = sr / 5)
        val dir = tmp()
        val res = sep.separate(MemoryPcmSource(stereo), dir, JobContext())
        RawFloatFile(res.stems[0].file, sr, 2).use { a -> RawFloatFile(res.stems[1].file, sr, 2).use { b ->
            assertEquals(mix.size.toLong(), a.frameCount)
            val ca = a.readChannels(0, mix.size); val cb = b.readChannels(0, mix.size)
            for (i in mix.indices step 7) { assertEquals(mix[i], ca[0][i] + cb[0][i], 1e-5f); assertEquals(tone[i] * 0.25f, ca[1][i], 1e-5f) }
        } }
        dir.deleteRecursively()
    }

    @Test fun flagsInseparableStems() {
        val dir = tmp()
        fun stem(k: StemKind, x: FloatArray) = Stem(k, File(dir, "${k.name}.f32").also { f -> RawFloatFile.Writer(f, 1).use { it.writeInterleaved(x) } }, sr, 1)
        val rep = SeparabilityAnalyzer.analyze(listOf(stem(StemKind.GUITAR, tone), stem(StemKind.PIANO, FloatArray(tone.size) { tone[it] * 0.9f }), stem(StemKind.DRUMS, clicks)))
        assertTrue(rep.warnings.toString(), rep.warnings.any { it.contains("Chitarra e Pianoforte non sono separabili") })
        assertTrue(rep.warnings.none { it.contains("Batteria e") })
        dir.deleteRecursively()
    }
}
