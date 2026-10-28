package org.ampy.core

import org.ampy.core.NoteEdits.addNote
import org.ampy.core.NoteEdits.deleteNote
import org.ampy.core.NoteEdits.moveNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun sample(): Project = Project(
        id = "p1", title = "Prova \"àèì\"\n", sourceAudio = "brano.flac", durationSec = 12.5,
        tempo = listOf(TempoChange(0.0, 96.0), TempoChange(8.0, 100.5)),
        key = KeySignature(9, true), timeSignature = TimeSignature(6, 8),
        tracks = listOf(
            Track("t1", "Basso", InstrumentKind.BASS, tuning = Tuning.BASS_4, warnings = listOf("leakage"),
                notes = listOf(NoteEvent(40, 0.5, 1.0, 90, 0.7f, listOf(0, 1, -2)), NoteEvent(43, 1.0, 1.5))),
        ),
        sections = listOf(Section("Intro", 0.0, 4.0)),
        chords = listOf(ChordMark(0.0, 2.0, 9, "MINOR", 0, 0.8)),
        notes = listOf("Chitarra e Pianoforte non separabili"),
    )

    @Test fun jsonRoundTrip() {
        val p = sample()
        val back = ProjectCodec.decode(ProjectCodec.encode(p))
        assertEquals(p, back)
    }

    @Test fun migratesSchema0() {
        val legacy = """{"id":"x","title":"vecchio","bpm":133,"tracks":[]}"""
        val p = ProjectCodec.decode(legacy)
        assertEquals(133.0, p.initialBpm, 1e-9)
        assertEquals(Project.CURRENT_SCHEMA, p.schemaVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFutureSchema() { ProjectCodec.decode("""{"schemaVersion":999}""") }

    @Test fun undoRedo() {
        val p = sample()
        val h = History(p)
        val noteId = p.tracks[0].notes[0].id
        h.apply("Sposta") { it.moveNote("t1", noteId, 0.25, 2) }
        assertEquals(42, h.current.tracks[0].notes[0].pitch)
        assertTrue(h.current.tracks[0].notes[0].userEdited)
        h.apply("Aggiungi") { it.addNote("t1", NoteEvent(50, 3.0, 3.5)) }
        h.apply("Elimina") { it.deleteNote("t1", noteId) }
        assertEquals(2, h.current.tracks[0].notes.size)
        h.undo(); h.undo()
        assertEquals(2, h.current.tracks[0].notes.size)
        assertEquals(42, h.current.tracks[0].notes.first { it.id == noteId }.pitch)
        h.undo()
        assertEquals(p, h.current)
        assertFalse(h.canUndo)
        h.redo()
        assertEquals("Aggiungi", h.redoLabel)
    }

    @Test fun keySignatureFifths() {
        assertEquals(0, KeySignature(9, true).fifths)   // La minore
        assertEquals(1, KeySignature(7, false).fifths)  // Sol maggiore
        assertEquals(-3, KeySignature(0, true).fifths)  // Do minore
        assertEquals(-2, KeySignature(10, false).fifths) // Sib maggiore
        assertEquals(4, KeySignature(4, false).fifths)  // Mi maggiore
    }

    @Test fun jsonParsesEscapesAndNumbers() {
        val j = Json.parse("""{"a":[1,-2.5e3,true,null,"xè\n"],"b":{}}""") as Json.Obj
        val a = j.arr("a")!!.items
        assertEquals(-2500.0, (a[1] as Json.Num).value, 0.0)
        assertEquals("xè\n", (a[4] as Json.Str).value)
    }
}
