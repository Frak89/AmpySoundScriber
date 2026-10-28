package org.ampy.audioengine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AudioEngineTest {
    private fun sine(freq: Double, sr: Int, n: Int, amp: Double = 0.5) = FloatArray(n) { (amp * sin(2 * PI * freq * it / sr)).toFloat() }

    @Test fun fftMatchesDft() {
        val n = 64
        val rnd = java.util.Random(7)
        val x = DoubleArray(n) { rnd.nextGaussian() }
        val re = x.copyOf(); val im = DoubleArray(n)
        Fft(n).transform(re, im)
        for (k in 0 until n) {
            var sr = 0.0; var si = 0.0
            for (t in 0 until n) { sr += x[t] * cos(-2 * PI * k * t / n); si += x[t] * sin(-2 * PI * k * t / n) }
            assertEquals(sr, re[k], 1e-9); assertEquals(si, im[k], 1e-9)
        }
        Fft(n).transform(re, im, inverse = true)
        for (t in 0 until n) assertEquals(x[t], re[t], 1e-9)
    }

    @Test fun stftPerfectReconstruction() {
        val x = FloatArray(10000) { (sin(it * 0.01) + 0.3 * sin(it * 0.37)).toFloat() }
        val st = Stft(1024, 256)
        val s = st.forward(x)
        val y = st.inverse(s.mag, s.phase, x.size)
        var err = 0.0
        for (i in x.indices) err = maxOf(err, abs((x[i] - y[i]).toDouble()))
        assertTrue("errore max $err", err < 1e-4)
    }

    private fun dominantFreq(x: FloatArray, sr: Int): Double {
        val n = 8192
        val re = DoubleArray(n) { if (it < x.size) x[it] * Windows.hann(n)[it] else 0.0 }; val im = DoubleArray(n)
        Fft(n).transform(re, im)
        var best = 0; var bm = 0.0
        for (k in 1 until n / 2) { val m = re[k] * re[k] + im[k] * im[k]; if (m > bm) { bm = m; best = k } }
        return best.toDouble() * sr / n
    }

    @Test fun resamplerPreservesPitchAndStreamingMatchesBatch() {
        val x = sine(440.0, 44100, 44100)
        val r = Resampler(44100, 22050)
        val y = r.process(x)
        assertEquals(22050, y.size)
        assertEquals(440.0, dominantFreq(y.copyOfRange(2000, 2000 + 8192), 22050), 22050.0 / 8192)
        // ampiezza preservata
        assertEquals(0.5, AudioMath.peak(y.copyOfRange(1000, 20000)).toDouble(), 0.01)
        // streaming a blocchi irregolari == batch
        val rs = Resampler(44100, 22050)
        val parts = ArrayList<Float>()
        var pos = 0; val sizes = intArrayOf(1000, 4096, 333, 10000)
        var si = 0
        while (pos < x.size) {
            val len = minOf(sizes[si++ % sizes.size], x.size - pos)
            val last = pos + len >= x.size
            parts.addAll(rs.processBlock(x.copyOfRange(pos, pos + len), last).toList())
            pos += len
        }
        val ys = parts.toFloatArray()
        val n = minOf(ys.size, y.size)
        assertTrue("lunghezza ${ys.size} vs ${y.size}", abs(ys.size - y.size) <= 1)
        for (i in 0 until n) assertEquals("idx $i", y[i], ys[i], 1e-5f)
    }

    @Test fun resamplerUpsample() {
        val x = sine(1000.0, 22050, 22050)
        val y = Resampler(22050, 48000).process(x)
        assertEquals(48000, y.size)
        assertEquals(1000.0, dominantFreq(y.copyOfRange(4000, 4000 + 8192), 48000), 48000.0 / 8192)
    }

    @Test fun wavRoundTrip() {
        val f = File.createTempFile("ampy", ".wav")
        val l = sine(220.0, 16000, 3000); val r = sine(330.0, 16000, 3000)
        WavCodec.write16(f, AudioBuffer(16000, arrayOf(l, r)))
        val back = WavCodec.read(f)
        assertEquals(2, back.channelCount); assertEquals(16000, back.sampleRate); assertEquals(3000, back.frames)
        for (i in 0 until 3000) { assertEquals(l[i], back.channels[0][i], 2e-4f); assertEquals(r[i], back.channels[1][i], 2e-4f) }
        f.delete()
    }

    @Test fun rawFloatFileStreaming() {
        val f = File.createTempFile("ampy", ".f32")
        RawFloatFile.Writer(f, 2).use { w -> repeat(10) { b -> w.writeInterleaved(FloatArray(2000) { (b * 1000 + it / 2).toFloat() }) } }
        RawFloatFile(f, 44100, 2).use { src ->
            assertEquals(10000L, src.frameCount)
            val m = src.readMono(4990, 20)
            assertEquals(4990f, m[0], 0f)
            assertEquals(20, m.size)
            val tail = src.readMono(9995, 100)
            assertEquals(5, tail.size)
        }
        f.delete()
    }

    @Test fun segmenterCoversEverythingOnce() {
        for (total in listOf(1L, 999L, 1000L, 12345L, 100000L)) {
            val segs = Segmenter.plan(total, 1000, 200)
            var covered = 0L; var prevEnd = 0L
            for (s in segs) {
                assertEquals(prevEnd, s.coreStart)
                assertTrue(s.coreEnd > s.coreStart)
                assertTrue(s.start <= s.coreStart && s.coreEnd <= s.start + s.length)
                covered += s.coreEnd - s.coreStart; prevEnd = s.coreEnd
            }
            assertEquals(total, covered)
        }
    }

    @Test fun mixerMuteSoloPan() {
        val a = FloatArray(4) { 1f }; val b = FloatArray(4) { 0.5f }
        val out = Mixer.mix(listOf(
            Mixer.Channel(a, a, 1f, -1f, muted = false, solo = true),
            Mixer.Channel(b, b, 1f, 0f, muted = false, solo = false),
        ), 4)
        assertEquals(Math.sqrt(2.0).toFloat(), out[0][0], 1e-5f) // pan tutto a sinistra
        assertEquals(0f, out[1][0], 1e-5f)
        val muted = Mixer.mix(listOf(Mixer.Channel(a, a, 1f, 0f, true, false)), 4)
        assertArrayEquals(FloatArray(4), muted[0], 0f)
    }

    @Test fun waveformPeaks() {
        val x = sine(5.0, 1000, 1000)
        val (mn, mx) = WaveformPeaks.compute(MemoryPcmSource(AudioBuffer.mono(1000, x)), 10, blockFrames = 77)
        assertEquals(10, mn.size)
        assertTrue(mx.max() > 0.49f && mn.min() < -0.49f)
    }

    @Test fun convertToRateStereoAndMono() {
        val l = sine(440.0, 48000, 48000); val r = sine(880.0, 48000, 48000)
        val src = MemoryPcmSource(AudioBuffer(48000, arrayOf(l, r)))
        val out = File.createTempFile("ampy", ".f32")
        PcmConvert.toRate(src, 44100, out, block = 5000).use { res ->
            assertEquals(2, res.channelCount); assertTrue(abs(res.frameCount - 44100) <= 2)
            val ch = res.readChannels(0, 44100)
            assertEquals(440.0, dominantFreq(ch[0].copyOfRange(4000, 4000 + 8192), 44100), 44100.0 / 8192)
            assertEquals(880.0, dominantFreq(ch[1].copyOfRange(4000, 4000 + 8192), 44100), 44100.0 / 8192)
        }
        PcmConvert.toMono(src, 22050, out).use { assertEquals(1, it.channelCount); assertTrue(abs(it.frameCount - 22050) <= 2) }
        out.delete()
    }
}
