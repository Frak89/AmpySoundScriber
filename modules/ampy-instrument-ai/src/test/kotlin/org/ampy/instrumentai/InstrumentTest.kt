package org.ampy.instrumentai

import org.ampy.core.InstrumentKind
import org.ampy.core.NoteEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentTest {
    @Test fun bassLineRecognised() {
        val notes = (0 until 32).map { NoteEvent(listOf(33, 36, 38, 40)[it % 4], it * 0.5, it * 0.5 + 0.45) }
        val g = InstrumentClassifier.classify(InstrumentClassifier.features(null, 22050, notes))
        assertEquals(InstrumentKind.BASS, g.kind)
        assertTrue(g.confidence <= 0.8)
    }

    @Test fun noiseIsDrums() {
        val rnd = java.util.Random(2)
        val x = FloatArray(22050 * 3).also { a -> var t = 0; while (t < a.size) { for (i in 0 until 800) if (t + i < a.size) a[t + i] = (rnd.nextGaussian() * Math.exp(-i / 150.0)).toFloat(); t += 5512 } }
        val g = InstrumentClassifier.classify(InstrumentClassifier.features(x, 22050, emptyList()))
        assertEquals(InstrumentKind.DRUMS, g.kind)
    }

    @Test fun widePolyphonicIsPiano() {
        val notes = (0 until 16).flatMap { i -> listOf(36 + i % 5, 60 + i % 7, 64 + i % 7, 67 + i % 7, 84 - i % 5).map { NoteEvent(it, i * 1.0, i * 1.0 + 0.9) } }
        assertEquals(InstrumentKind.PIANO, InstrumentClassifier.classify(InstrumentClassifier.features(null, 22050, notes)).kind)
    }
}
