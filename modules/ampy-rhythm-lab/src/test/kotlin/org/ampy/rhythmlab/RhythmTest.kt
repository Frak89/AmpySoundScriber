package org.ampy.rhythmlab

import org.ampy.core.NoteEvent
import org.ampy.core.TempoChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class RhythmTest {
    /** Click track sintetico: impulsi di rumore filtrato a [bpm]. */
    private fun clicks(bpm: Double, sr: Int, seconds: Double, bpm2: Double? = null, switchAt: Double = 0.0): FloatArray {
        val x = FloatArray((sr * seconds).toInt())
        val rnd = java.util.Random(3)
        var t = 0.0
        while (t < seconds) {
            val start = (t * sr).toInt()
            for (i in 0 until (0.03 * sr).toInt()) {
                if (start + i >= x.size) break
                x[start + i] += (rnd.nextGaussian() * exp(-i / (0.005 * sr)) * 0.5 + 0.3 * sin(2 * PI * 1000 * i / sr) * exp(-i / (0.01 * sr))).toFloat()
            }
            val cur = if (bpm2 != null && t >= switchAt) bpm2 else bpm
            t += 60.0 / cur
        }
        return x
    }

    @Test fun tempoOfClickTrack() {
        for (bpm in listOf(90.0, 120.0, 140.0)) {
            val env = OnsetEnvelope.compute(clicks(bpm, 22050, 20.0), 22050)
            val est = TempoEstimator.estimate(env)
            assertEquals("bpm $bpm", bpm, est.bpm, bpm * 0.02)
        }
    }

    @Test fun detectsTempoChange() {
        val env = OnsetEnvelope.compute(clicks(100.0, 22050, 60.0, bpm2 = 130.0, switchAt = 30.0), 22050)
        val curve = TempoEstimator.tempoCurve(env)
        assertEquals(100.0, curve.first().bpm, 3.0)
        assertTrue("curva $curve", curve.any { abs(it.bpm - 130.0) < 4 && it.timeSec in 20.0..40.0 })
    }

    @Test fun onsetPeaksAndBeats() {
        val env = OnsetEnvelope.compute(clicks(120.0, 22050, 10.0), 22050)
        val peaks = env.peaks()
        assertEquals(20.0, peaks.size.toDouble(), 1.0)
        val beats = BeatTracker.track(env, 120.0)
        val diffs = beats.zipWithNext { a, b -> b - a }
        assertEquals(0.5, diffs.average(), 0.02)
    }

    @Test fun tempoMapRoundTrip() {
        val tm = TempoMap(listOf(TempoChange(0.0, 120.0), TempoChange(10.0, 60.0)))
        assertEquals(20.0, tm.secToBeat(10.0), 1e-9)
        assertEquals(21.0, tm.secToBeat(11.0), 1e-9)
        for (b in listOf(0.0, 5.5, 20.0, 33.3)) assertEquals(b, tm.secToBeat(tm.beatToSec(b)), 1e-9)
    }

    @Test fun quantizeAndSubdivision() {
        val tm = TempoMap(listOf(TempoChange(0.0, 120.0)))
        // terzine di croma a 120 bpm: 1/3 di beat = 0.1667 s
        val triplets = (0 until 12).map { it / 3.0 + 0.01 }
        assertEquals(3, Quantizer.detectSubdivision(triplets))
        val eighths = (0 until 16).map { it / 2.0 + 0.02 }
        assertEquals(2, Quantizer.detectSubdivision(eighths))
        val q = Quantizer.quantize(listOf(NoteEvent(60, 0.26, 0.49)), tm, 4, 480)
        assertEquals(240L, q[0].startTick)  // 0.26 s = 0.52 beat -> griglia 1/4 di beat -> 0.5 beat = 240 tick
        assertEquals(240L, q[0].durTicks)
    }

    @Test fun drumsBands() {
        val sr = 22050
        val x = FloatArray(sr * 2)
        // cassa (60 Hz) a 0.5s, charleston (rumore acuto) a 1.0s
        for (i in 0 until 4000) x[sr / 2 + i] += (0.8 * sin(2 * PI * 60 * i / sr) * exp(-i / 1500.0)).toFloat()
        val rnd = java.util.Random(1)
        for (i in 0 until 600) x[sr + i] += (rnd.nextGaussian() * 0.3 * exp(-i / 200.0)).toFloat()
        val hits = DrumTranscriber.transcribe(x, sr)
        assertTrue(hits.any { it.pitch == 36 && abs(it.startSec - 0.5) < 0.05 })
        assertTrue(hits.any { it.pitch == 42 && abs(it.startSec - 1.0) < 0.05 })
    }
}
