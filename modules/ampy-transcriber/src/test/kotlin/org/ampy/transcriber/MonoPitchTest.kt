package org.ampy.transcriber

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class MonoPitchTest {
    @Test fun melodyRecovered() {
        val sr = 22050
        val melody = listOf(57, 60, 64, 62, 45, 69) // include un La basso (110 Hz)
        val noteSec = 0.4
        val x = FloatArray((sr * noteSec * melody.size).toInt() + sr / 4)
        melody.forEachIndexed { i, p ->
            val f = 440.0 * 2.0.pow((p - 69) / 12.0)
            val s = (i * noteSec * sr).toInt()
            for (k in 0 until (noteSec * 0.9 * sr).toInt()) {
                val t = k.toDouble() / sr
                x[s + k] += (0.4 * (sin(2 * PI * f * t) + 0.5 * sin(4 * PI * f * t) + 0.25 * sin(6 * PI * f * t)) * minOf(1.0, t / 0.01)).toFloat()
            }
        }
        val notes = MonoPitchTranscriber(sr).transcribe(x)
        assertEquals(melody, notes.map { it.pitch })
        notes.forEachIndexed { i, n -> assertEquals(i * noteSec, n.startSec, 0.04) }
    }
}
