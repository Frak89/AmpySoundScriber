package org.ampy.midilab

import org.ampy.core.InstrumentKind
import org.ampy.core.KeySignature
import org.ampy.core.NoteEvent
import org.ampy.core.TempoChange
import org.ampy.core.TimeSignature
import org.ampy.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MidiTest {
    val tracks = listOf(
        Track("p", "Piano", InstrumentKind.PIANO, listOf(NoteEvent(60, 0.0, 0.5, 100), NoteEvent(64, 0.5, 1.0, 80), NoteEvent(67, 1.0, 3.0, 70, pitchBends = listOf(0, 1, 3, 1)))),
        Track("d", "Batteria", InstrumentKind.DRUMS, listOf(NoteEvent(36, 0.0, 0.1), NoteEvent(38, 0.5, 0.6))),
        Track("b", "Basso", InstrumentKind.BASS, listOf(NoteEvent(36, 2.0, 2.5))),
    )
    val tempo = listOf(TempoChange(0.0, 120.0), TempoChange(2.0, 90.0))

    @Test fun roundTrip() {
        val bytes = MidiWriter.write(tracks, tempo, KeySignature(9, true), TimeSignature(3, 4))
        val back = MidiReader.read(bytes)
        assertEquals(480, back.ppq)
        assertEquals(TimeSignature(3, 4), back.timeSignature)
        assertEquals(KeySignature(9, true), back.key)
        assertEquals(2, back.tempo.size); assertEquals(90.0, back.tempo[1].bpm, 0.01); assertEquals(2.0, back.tempo[1].timeSec, 1e-3)
        assertEquals(3, back.tracks.size)
        val piano = back.tracks[0]
        assertEquals(listOf(60, 64, 67), piano.notes.map { it.pitch })
        assertEquals(3.0, piano.notes[2].endSec, 2e-3)
        assertEquals(InstrumentKind.DRUMS, back.tracks[1].instrument)
        assertEquals(InstrumentKind.BASS, back.tracks[2].instrument)
        assertEquals(2.5, back.tracks[2].notes[0].endSec, 2e-3)
    }

    @Test(expected = InvalidMidiException::class)
    fun rejectsGarbage() { MidiReader.read(ByteArray(20) { 1 }) }

    @Test fun synthRendersAudio() {
        val buf = PreviewSynth.renderMix(tracks, 22050, 3.0)
        assertEquals(2, buf.channelCount)
        val energy = buf.channels[0].sumOf { (it * it).toDouble() }
        assertTrue(energy > 1.0)
        assertTrue(buf.channels[0].all { it in -1f..1f })
    }

    @Test fun chunkedRenderEqualsFull() {
        for (t in tracks) {
            val full = PreviewSynth.renderTrack(t, 8000, 3.0)
            val chunked = ArrayList<Float>()
            var pos = 0L
            while (pos < full.size) { val n = minOf(1777, full.size - pos.toInt()); chunked.addAll(PreviewSynth.renderRange(t, 8000, pos, n).toList()); pos += n }
            for (i in full.indices) assertEquals("${t.name} @$i", full[i], chunked[i], 1e-6f)
        }
    }

    /** Scrive un file di esempio usato dalla validazione esterna (tools/validate_exports.py). */
    @Test fun writesSampleForExternalValidation() {
        val dir = File(System.getProperty("ampy.testdata", "testdata"), "out").apply { mkdirs() }
        File(dir, "sample.mid").writeBytes(MidiWriter.write(tracks, tempo, KeySignature(9, true), TimeSignature(3, 4)))
    }
}
