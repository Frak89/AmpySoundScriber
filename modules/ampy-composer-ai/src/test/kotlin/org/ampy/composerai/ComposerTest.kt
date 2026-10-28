package org.ampy.composerai

import org.ampy.core.InstrumentKind
import org.ampy.core.KeySignature
import org.ampy.midilab.MidiReader
import org.ampy.midilab.MidiWriter
import org.ampy.musicbrain.KeyDetector
import org.ampy.musicbrain.Scale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComposerTest {
    @Test fun fullSongHasRequestedLengthAndStructure() {
        for (genre in Genre.entries) for (dur in listOf(180.0, 420.0)) {
            val req = ComposeRequest(genre = genre, durationSec = dur, seed = 7, tonic = 2, minor = genre != Genre.POP)
            val p = HierarchicalComposer(req).compose()
            val barSec = 4 * 60.0 / p.initialBpm
            assertTrue("$genre $dur: durata ${p.durationSec}", kotlin.math.abs(p.durationSec - dur) <= 4 * barSec + 1e-6)
            val labels = p.sections.map { it.label }
            assertTrue("$genre: $labels", labels.first() == SectionType.INTRO.label && labels.contains(SectionType.CHORUS.label))
            if (genre != Genre.CLASSICAL) assertTrue("$genre: manca assolo", labels.contains(SectionType.SOLO.label))
            assertTrue("$genre: tracce ${p.tracks.map { it.name }}", p.tracks.size >= 3)
            // ogni traccia copre il brano (niente frammenti di pochi secondi)
            for (t in p.tracks.filter { it.name != "Assolo" }) assertTrue("$genre ${t.name}", t.notes.maxOf { it.endSec } > p.durationSec * 0.75)
        }
    }

    @Test fun chorusesAreCoherentAndKeyIsRespected() {
        val c = HierarchicalComposer(ComposeRequest(genre = Genre.ROCK, durationSec = 240.0, seed = 3, tonic = 4, minor = true, complexity = 0.4))
        val plans = c.planSections()
        val choruses = plans.filter { it.type == SectionType.CHORUS }
        assertTrue(choruses.size >= 2)
        assertEquals("i ritornelli devono condividere l'armonia", choruses[0].chords.take(4), choruses[1].chords.take(4))
        val p = c.compose()
        val mel = p.tracks.first { it.name == "Melodia" }.notes
        val scale = Scale(4, org.ampy.musicbrain.ScaleType.NATURAL_MINOR)
        val inScale = mel.count { scale.contains(it.pitch) }.toDouble() / mel.size
        assertTrue("note in scala $inScale", inScale > 0.9)
        assertEquals(KeySignature(4, true), KeyDetector.detect(p.tracks.flatMap { it.notes.filter { n -> it.instrument != InstrumentKind.DRUMS } }).key)
        // melodia monofonica: nessuna sovrapposizione
        val sorted = mel.sortedBy { it.startSec }
        for (i in 1 until sorted.size) assertTrue(sorted[i].startSec >= sorted[i - 1].startSec)
    }

    @Test fun deterministicWithSeedAndExportable() {
        val a = HierarchicalComposer(ComposeRequest(genre = Genre.JAZZ, seed = 11, durationSec = 200.0)).compose()
        val b = HierarchicalComposer(ComposeRequest(genre = Genre.JAZZ, seed = 11, durationSec = 200.0)).compose()
        assertEquals(a.tracks.map { it.notes.map { n -> n.pitch to n.startSec } }, b.tracks.map { it.notes.map { n -> n.pitch to n.startSec } })
        val midi = MidiWriter.write(a)
        val back = MidiReader.read(midi)
        assertEquals(a.tracks.size, back.tracks.size)
        File(File(System.getProperty("ampy.testdata", "testdata"), "out").apply { mkdirs() }, "composed_jazz.mid").writeBytes(midi)
    }

    @Test fun referenceStyleOnlyGeneralFeatures() {
        val ref = HierarchicalComposer(ComposeRequest(genre = Genre.FUNK, seed = 5, bpm = 104.0, tonic = 7, minor = false)).compose()
        val style = ReferenceStyle.analyze(ref.tracks.filter { !it.instrument.isDrum }.flatMap { it.notes }, ref.initialBpm, emptyList())
        assertEquals(104.0, style.bpm, 1e-9)
        val req = style.applyTo(ComposeRequest(genre = Genre.FUNK, seed = 99))
        val newSong = HierarchicalComposer(req).compose()
        val refMel = ref.tracks.first { it.name == "Melodia" }.notes.map { it.pitch }
        val newMel = newSong.tracks.first { it.name == "Melodia" }.notes.map { it.pitch }
        assertTrue("la melodia non deve essere copiata", refMel.take(16) != newMel.take(16))
    }
}
