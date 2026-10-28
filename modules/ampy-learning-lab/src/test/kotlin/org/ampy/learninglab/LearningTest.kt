package org.ampy.learninglab

import org.ampy.core.Json
import org.ampy.core.NoteEvent
import org.ampy.midilab.MidiReader
import org.ampy.transcriber.Activations
import org.ampy.transcriber.BasicPitchParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LearningTest {
    private val bp = File(System.getProperty("ampy.testdata", "testdata"), "bp")
    private fun f32(name: String): FloatArray { val fb = ByteBuffer.wrap(File(bp, name).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); return FloatArray(fb.remaining()).also { fb.get(it) } }
    private fun refMidi() = MidiReader.read(File(bp, "reference.mid").readBytes()).tracks.flatMap { it.notes }
    private fun bpNotes() = (Json.parse(File(bp, "notes_default.json").readText()) as Json.Arr).items.map { r -> val a = (r as Json.Arr).items; NoteEvent((a[2] as Json.Num).value.toInt(), (a[0] as Json.Num).value, (a[1] as Json.Num).value) }

    @Test fun metricsMatchMirEval() {
        assumeTrue(File(bp, "metrics_mir_eval.json").exists())
        val exp = Json.parse(File(bp, "metrics_mir_eval.json").readText()) as Json.Obj
        fun e(k: String) = exp.arr(k)!!.items.map { (it as Json.Num).value }
        val r = TranscriptionMetrics.evaluate(refMidi(), bpNotes())
        for ((k, prf) in listOf("note_onset" to r.noteOnset, "note_onset_offset" to r.noteOnsetOffset, "onset_only" to r.onsetOnly, "offset_only" to r.offsetOnly)) {
            val x = e(k)
            assertEquals("$k precision", x[0], prf.precision, 1e-9)
            assertEquals("$k recall", x[1], prf.recall, 1e-9)
            assertEquals("$k f1", x[2], prf.f1, 1e-9)
        }
        println("Basic Pitch su brano sintetico di prova:\n" + r.toText())
    }

    @Test fun metricsEdgeCases() {
        val ref = listOf(NoteEvent(60, 0.0, 1.0), NoteEvent(64, 0.5, 1.0))
        assertEquals(1.0, TranscriptionMetrics.evaluate(ref, ref).noteOnsetOffset.f1, 1e-12)
        val octave = listOf(NoteEvent(72, 0.01, 1.0), NoteEvent(64, 0.52, 1.0))
        val rep = TranscriptionMetrics.evaluate(ref, octave)
        assertEquals(0.5, rep.noteOnset.f1, 1e-12)
        assertEquals(1.0, rep.chromaAccuracy, 1e-12)
        assertEquals(0.0, TranscriptionMetrics.evaluate(ref, emptyList()).noteOnset.f1, 0.0)
    }

    @Test fun separationMetrics() {
        val s = FloatArray(1000) { kotlin.math.sin(it * 0.1).toFloat() }
        val noisy = FloatArray(1000) { s[it] + 0.1f * kotlin.math.sin(it * 1.7).toFloat() }
        val scaled = FloatArray(1000) { s[it] * 0.5f }
        assertTrue(SeparationMetrics.sdr(s, noisy) in 15.0..25.0)
        assertTrue("SI-SDR invariante alla scala", SeparationMetrics.siSdr(s, scaled) > 100)
    }

    @Test fun tunerImprovesOrKeepsF1AndPersists() {
        assumeTrue(bp.exists())
        val frames = (Json.parse(File(bp, "meta.json").readText()) as Json.Obj).int("frames")!!
        val store = LearningStore(kotlin.io.path.createTempDirectory("learn").toFile())
        val tuner = ParameterTuner(store)
        val case = ParameterTuner.Case(Activations(frames, 88, f32("note.f32")), Activations(frames, 88, f32("onset.f32")), 0, refMidi())
        val res = tuner.tune("PIANO", listOf(case), BasicPitchParams())
        println("Adattamento: F1 %.4f -> %.4f (%d combinazioni)".format(res.before, res.after, res.evaluated))
        assertTrue(res.after >= res.before)
        val loaded = tuner.load("PIANO", BasicPitchParams())
        assertEquals(res.params.onsetThreshold, loaded.onsetThreshold, 0.0)
        assertTrue(File(store.configDir, "transcription_PIANO.json").exists())
        // cartelle separate
        assertTrue(store.feedbackDir.exists() && store.datasetDir.exists() && store.checkpointDir.exists())
        store.root.deleteRecursively()
    }

    @Test fun feedbackAndDatasetExport() {
        val store = LearningStore(kotlin.io.path.createTempDirectory("learn").toFile())
        val original = listOf(NoteEvent(60, 0.0, 0.5), NoteEvent(72, 0.5, 1.0), NoteEvent(65, 1.2, 1.25))
        val corrected = listOf(NoteEvent(60, 0.0, 0.5), NoteEvent(60, 0.5, 1.0), NoteEvent(40, 1.5, 2.0))
        store.saveCorrection("p", "t", "PIANO", "brano.wav", original, corrected)
        val st = store.errorStats()
        assertEquals(1, st.corrections); assertEquals(2, st.falsePositives); assertEquals(2, st.misses)
        assertEquals(1, st.octaveErrors); assertEquals(1, st.lowRegisterMisses)
        val ds = store.exportDataset("ds1")
        assertTrue(File(ds, "index.csv").readText().lines().size >= 2)
        store.root.deleteRecursively()
    }
}
