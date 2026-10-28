package org.ampy.scoreengine

import org.ampy.core.InstrumentKind
import org.ampy.core.KeySignature
import org.ampy.core.NoteEvent
import org.ampy.core.Project
import org.ampy.core.TempoChange
import org.ampy.core.TimeSignature
import org.ampy.core.Track
import org.ampy.core.Tuning
import org.ampy.musicbrain.Chord
import org.ampy.musicbrain.ChordQuality
import org.ampy.musicbrain.ChordSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScoreTest {
    // 100 bpm: un quarto = 0.6 s
    private val q = 0.6
    private fun n(p: Int, startQ: Double, durQ: Double) = NoteEvent(p, startQ * q, (startQ + durQ) * q, 90)

    private val project = Project(
        id = "s", title = "Prova & <spartito>", tempo = listOf(TempoChange(0.0, 100.0)), key = KeySignature(10, false), timeSignature = TimeSignature(4, 4),
        tracks = listOf(
            Track("pf", "Pianoforte", InstrumentKind.PIANO, listOf(
                n(70, 0.0, 1.0), n(74, 1.0, 1.0), n(77, 2.0, 3.0), // la terza nota attraversa la stanghetta
                n(46, 0.0, 4.0), n(53, 0.0, 4.0), n(58, 4.0, 2.0),
                n(72, 6.0, 1.0 / 3), n(74, 6.0 + 1.0 / 3, 1.0 / 3), n(75, 6.0 + 2.0 / 3, 1.0 / 3),
            )),
            Track("gt", "Chitarra", InstrumentKind.GUITAR, listOf(n(40, 0.0, 1.0), n(47, 1.0, 0.5), n(52, 1.5, 0.5), n(55, 2.0, 2.0), n(59, 2.0, 2.0), n(64, 2.0, 2.0)), tuning = Tuning.GUITAR_STANDARD),
            Track("dr", "Batteria", InstrumentKind.DRUMS, listOf(n(36, 0.0, 0.25), n(42, 0.5, 0.25), n(38, 1.0, 0.25), n(42, 1.5, 0.25))),
        ),
    )

    @Test fun musicXmlRoundTrip() {
        val chords = listOf(ChordSegment(0.0, 4 * q, Chord(10, ChordQuality.MAJOR), 1.0), ChordSegment(4 * q, 8 * q, Chord(3, ChordQuality.MAJ7, bass = 7), 1.0))
        val xml = MusicXmlWriter.write(project, opt = ScoreOptions(chords = chords, tabTracks = setOf("gt")))
        assertTrue(xml.contains("Prova &amp; &lt;spartito&gt;"))
        assertTrue(xml.contains("<fifths>-2</fifths>"))
        assertTrue(xml.contains("<tie type=\"start\"/>"))
        assertTrue(xml.contains("<actual-notes>3</actual-notes>"))
        assertTrue(xml.contains("<sign>TAB</sign>"))
        assertTrue(xml.contains("<kind text=\"maj7\">major-seventh</kind>"))
        val dir = File(System.getProperty("ampy.testdata", "testdata"), "out").apply { mkdirs() }
        File(dir, "sample.musicxml").writeText(xml)

        val back = MusicXmlReader.read(xml)
        assertEquals(3, back.tracks.size) // il rigo TAB non viene reimportato come traccia separata
        assertEquals(KeySignature(10, false), back.key)
        assertEquals(100.0, back.tempo[0].bpm, 1e-6)
        val pf = back.tracks[0].notes
        // la nota legata oltre la stanghetta ritorna una sola nota di 3 quarti
        val f5 = pf.single { it.pitch == 77 }
        assertEquals(2 * q, f5.startSec, 1e-6); assertEquals(5 * q, f5.endSec, 1e-6)
        val trip = pf.filter { it.pitch in listOf(72, 74, 75) && it.startSec >= 6 * q - 1e-6 }
        assertEquals(3, trip.size); assertEquals((6 + 1.0 / 3) * q, trip[1].startSec, 1e-6)
        assertEquals(InstrumentKind.DRUMS, back.tracks[2].instrument)
    }

    @Test fun tabAssignmentIsPlayable() {
        val gen = TabGenerator(Tuning.GUITAR_STANDARD)
        val res = gen.assign(project.tracks[1].notes)
        assertEquals(6, res.notes.size)
        val e2 = res.notes.first { it.note.pitch == 40 }
        assertEquals(0 to 0, e2.string to e2.fret)
        // accordo G-B-E (55,59,64): corde distinte, estensione <= 4
        val chord = res.notes.filter { it.note.pitch in listOf(55, 59, 64) }
        assertEquals(3, chord.map { it.string }.toSet().size)
        for (t in res.notes) assertEquals(t.note.pitch, Tuning.GUITAR_STANDARD.openStrings[t.string] + t.fret)
        val ascii = gen.renderAscii(res)
        assertTrue(ascii.lines().any { it.startsWith("E |") })
    }

    @Test fun tabTransposesOutOfRange() {
        val res = TabGenerator(Tuning.BASS_4).assign(listOf(NoteEvent(20, 0.0, 1.0), NoteEvent(90, 1.0, 2.0)))
        assertEquals(2, res.transposed)
        for (t in res.notes) assertEquals((t.note.pitch % 12), (Tuning.BASS_4.openStrings[t.string] + t.fret) % 12)
    }
}
