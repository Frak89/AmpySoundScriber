package org.ampy.musicbrain

import org.ampy.core.KeySignature
import org.ampy.core.NoteEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MusicBrainTest {
    private fun chordNotes(c: Chord, start: Double, dur: Double) = c.voicing(60).map { NoteEvent(it, start, start + dur, 90) } + NoteEvent(36 + c.bass, start, start + dur, 90)

    @Test fun keyFromCadence() {
        val scale = Scale(7, ScaleType.MAJOR) // Sol maggiore
        val prog = listOf(0, 3, 4, 0, 5, 3, 4, 0).map { scale.diatonicChord(it) }
        val notes = prog.flatMapIndexed { i, c -> chordNotes(c, i * 1.0, 1.0) }
        assertEquals(KeySignature(7, false), KeyDetector.detect(notes).key)
        val am = Scale(9, ScaleType.HARMONIC_MINOR)
        val notesMin = listOf(0, 3, 4, 0, 3, 4, 0).map { am.diatonicChord(it) }.flatMapIndexed { i, c -> chordNotes(c, i * 1.0, 1.0) }
        assertEquals(KeySignature(9, true), KeyDetector.detect(notesMin).key)
    }

    @Test fun diatonicChords() {
        val c = Scale(0, ScaleType.MAJOR)
        assertEquals("C", c.diatonicChord(0).symbol)
        assertEquals("Dm", c.diatonicChord(1).symbol)
        assertEquals("G7", c.diatonicChord(4, seventh = true).symbol)
        assertEquals("Bm7b5", c.diatonicChord(6, seventh = true).symbol)
        assertEquals(64, c.degreeToPitch(2)); assertEquals(59, c.degreeToPitch(-1))
    }

    @Test fun chordRecognitionFromNotes() {
        val chords = listOf(Chord(0, ChordQuality.MAJOR), Chord(9, ChordQuality.MINOR), Chord(5, ChordQuality.MAJOR), Chord(7, ChordQuality.DOM7))
        val notes = chords.flatMapIndexed { i, c -> chordNotes(c, i * 2.0, 2.0) }
        val segs = ChordRecognizer.recognize(notes, (0..8).map { it * 1.0 })
        assertEquals(listOf("C", "Am", "F", "G7"), segs.map { it.chord?.symbol })
        assertEquals(2.0, segs[1].startSec, 1e-9)
    }

    @Test fun inversionDetected() {
        val notes = listOf(64, 67, 72).map { NoteEvent(it, 0.0, 1.0) } + NoteEvent(40, 0.0, 1.0)
        val seg = ChordRecognizer.recognize(notes, listOf(0.0, 1.0))
        assertEquals("C/E", seg[0].chord?.symbol)
    }

    @Test fun chromaFromAudio() {
        val sr = 22050
        val freqs = listOf(261.63, 329.63, 392.0) // Do-Mi-Sol
        val x = FloatArray(sr * 2) { i -> freqs.sumOf { sin(2 * PI * it * i / sr) }.toFloat() * 0.2f }
        val ch = Chromagram.fromAudio(x, sr).sum()
        val top3 = ch.indices.sortedByDescending { ch[it] }.take(3).toSet()
        assertEquals(setOf(0, 4, 7), top3)
        assertEquals("C", ChordRecognizer.classify(ch)!!.first.symbol)
    }

    @Test fun keyChangeDetected() {
        val c = Scale(0, ScaleType.MAJOR); val d = Scale(2, ScaleType.MAJOR)
        val partA = (0 until 32).flatMap { i -> chordNotes(c.diatonicChord(listOf(0, 3, 4, 0)[i % 4]), i * 1.0, 1.0) }
        val partB = (0 until 32).flatMap { i -> chordNotes(d.diatonicChord(listOf(0, 3, 4, 0)[i % 4]), 32 + i * 1.0, 1.0) }
        val chroma = Chromagram.fromNotes(partA + partB, 0.5, 64.0)
        val changes = KeyDetector.keyChanges(chroma)
        assertEquals(KeySignature(0, false), changes.first().second)
        assertTrue("$changes", changes.any { it.second == KeySignature(2, false) && it.first in 24.0..36.0 })
    }

    @Test fun structureABA() {
        val a = DoubleArray(12).also { it[0] = 1.0; it[4] = 0.8; it[7] = 0.9 }
        val b = DoubleArray(12).also { it[2] = 1.0; it[5] = 0.8; it[9] = 0.9 }
        val bars = List(8) { a } + List(8) { b } + List(8) { a }
        val secs = StructureAnalyzer.analyze(bars, (0..24).map { it * 2.0 })
        assertEquals(listOf("A", "B", "A"), secs.map { it.label })
        assertEquals(16.0, secs[1].startSec, 1e-9)
        assertEquals(48.0, secs.last().endSec, 1e-9)
    }
}
