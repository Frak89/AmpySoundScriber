package org.ampy.transcriber

import org.ampy.audioengine.MemoryPcmSource
import org.ampy.audioengine.WavCodec
import org.ampy.core.CancellationToken
import org.ampy.core.CancelledException
import org.ampy.core.FloatTensor
import org.ampy.core.InferenceDevice
import org.ampy.core.JobContext
import org.ampy.core.Json
import org.ampy.core.NoteEvent
import org.ampy.core.ProgressSink
import org.ampy.core.TensorRunner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Conformità con l'implementazione Python originale di Basic Pitch.
 * I dati vengono generati da tools/reference/basic_pitch_reference.py (eseguito dal modello ONNX reale).
 */
class BasicPitchConformanceTest {
    private val dir = File(System.getProperty("ampy.testdata", "testdata"), "bp")

    private fun f32(name: String): FloatArray {
        val b = File(dir, name).readBytes()
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
    private val meta by lazy { Json.parse(File(dir, "meta.json").readText()) as Json.Obj }

    private data class Ref(val s: Double, val e: Double, val p: Int, val a: Double, val bends: List<Int>?)
    private fun refNotes(name: String) = (Json.parse(File(dir, name).readText()) as Json.Arr).items.map { it as Json.Arr }.map { r ->
        Ref((r.items[0] as Json.Num).value, (r.items[1] as Json.Num).value, (r.items[2] as Json.Num).value.toInt(), (r.items[3] as Json.Num).value,
            (r.items[4] as? Json.Arr)?.items?.map { (it as Json.Num).value.toInt() })
    }

    private fun assertSameNotes(ref: List<Ref>, got: List<NoteEvent>, checkBends: Boolean) {
        assertEquals("numero di note", ref.size, got.size)
        val r = ref.sortedWith(compareBy({ it.s }, { it.p }, { it.e }))
        val g = got.sortedWith(compareBy({ it.startSec }, { it.pitch }, { it.endSec }))
        for (i in r.indices) {
            assertEquals("pitch #$i", r[i].p, g[i].pitch)
            assertEquals("start #$i", r[i].s, g[i].startSec, 1e-9)
            assertEquals("end #$i", r[i].e, g[i].endSec, 1e-9)
            assertEquals("amp #$i", r[i].a, g[i].confidence.toDouble(), 1e-5)
            if (checkBends) assertEquals("bends #$i", r[i].bends, g[i].pitchBends)
        }
    }

    private fun activations(): Triple<Activations, Activations, Activations> {
        val n = meta.int("frames")!!
        return Triple(Activations(n, 88, f32("note.f32")), Activations(n, 88, f32("onset.f32")), Activations(n, 264, f32("contour.f32")))
    }

    @Test fun framingMatchesReference() {
        assumeTrue("dati di riferimento assenti: eseguire tools/reference/basic_pitch_reference.py", dir.exists())
        val audio = WavCodec.read(File(dir, "input.wav")).mono()
        assertEquals(meta.int("samples")!!, audio.size)
        val nWin = BasicPitchFraming.windowCount(audio.size.toLong())
        assertEquals(meta.int("n_windows")!!, nWin)
        val ref = f32("windows.f32")
        for (w in 0 until nWin) {
            val win = BasicPitchFraming.window(w, audio.size.toLong()) { s, c -> audio.copyOfRange(s.toInt(), s.toInt() + c) }
            assertArrayEquals("finestra $w", ref.copyOfRange(w * 43844, (w + 1) * 43844), win, 0f)
        }
        assertEquals(meta.int("frames")!!, BasicPitchFraming.outputFrames(audio.size.toLong()))
    }

    @Test fun unwrapMatchesReference() {
        assumeTrue(dir.exists())
        val nWin = meta.int("n_windows")!!; val frames = meta.int("frames")!!
        for ((name, bins) in listOf("note" to 88, "onset" to 88, "contour" to 264)) {
            val raw = f32("raw_$name.f32")
            val cat = (0 until nWin).flatMap { w -> BasicPitchFraming.trimWindow(raw.copyOfRange(w * 172 * bins, (w + 1) * 172 * bins), bins).toList() }.toFloatArray()
            assertArrayEquals(name, f32("$name.f32"), cat.copyOfRange(0, frames * bins), 0f)
        }
    }

    @Test fun notesDefaultParamsMatch() {
        assumeTrue(dir.exists())
        val (note, onset, contour) = activations()
        val p = BasicPitchParams()
        assertEquals(11, p.minNoteLenFrames)
        val fn = BasicPitchNotes.pitchBends(contour, BasicPitchNotes.outputToNotesPolyphonic(note, onset, p))
        assertSameNotes(refNotes("notes_default.json"), BasicPitchNotes.toNoteEvents(fn), checkBends = true)
    }

    @Test fun notesCustomParamsMatch() {
        assumeTrue(dir.exists())
        val (note, onset, _) = activations()
        val p = BasicPitchParams(onsetThreshold = 0.6, frameThreshold = 0.25, minimumNoteLengthMs = 8 / 86.1328125 * 1000, minimumFrequency = 60.0, maximumFrequency = 1000.0, melodiaTrick = false, includePitchBends = false)
        assertEquals(8, p.minNoteLenFrames)
        val fn = BasicPitchNotes.outputToNotesPolyphonic(note, onset, p)
        assertSameNotes(refNotes("notes_custom.json"), BasicPitchNotes.toNoteEvents(fn), checkBends = false)
    }

    /** Runner che restituisce le uscite registrate dal modello ONNX reale, verificando che l'input coincida. */
    private class ReplayRunner(val windows: FloatArray, val raw: Map<String, FloatArray>) : TensorRunner {
        override val inputNames = listOf(BasicPitchConstants.INPUT_NAME)
        override val outputNames = raw.keys.toList()
        override val activeDevice = InferenceDevice.CPU
        var calls = 0
        override fun run(inputs: Map<String, FloatTensor>): Map<String, FloatTensor> {
            calls++
            val x = inputs[BasicPitchConstants.INPUT_NAME]!!
            val b = x.shape[0]
            // individua le finestre per contenuto
            val idx = (0 until b).map { k ->
                val w = x.data.copyOfRange(k * 43844, (k + 1) * 43844)
                (0 until windows.size / 43844).first { i -> windows.copyOfRange(i * 43844, (i + 1) * 43844).contentEquals(w) }
            }
            fun pick(name: String, bins: Int) = FloatTensor(intArrayOf(b, 172, bins), idx.flatMap { i -> raw[name]!!.copyOfRange(i * 172 * bins, (i + 1) * 172 * bins).toList() }.toFloatArray())
            return mapOf(
                BasicPitchConstants.OUTPUT_NOTE to pick(BasicPitchConstants.OUTPUT_NOTE, 88),
                BasicPitchConstants.OUTPUT_ONSET to pick(BasicPitchConstants.OUTPUT_ONSET, 88),
                BasicPitchConstants.OUTPUT_CONTOUR to pick(BasicPitchConstants.OUTPUT_CONTOUR, 264),
            )
        }
        override fun close() {}
    }

    @Test fun endToEndPipelineWithCheckpointResume() {
        assumeTrue(dir.exists())
        val audio = WavCodec.read(File(dir, "input.wav"))
        val runner = ReplayRunner(f32("windows.f32"), mapOf(
            BasicPitchConstants.OUTPUT_NOTE to f32("raw_note.f32"), BasicPitchConstants.OUTPUT_ONSET to f32("raw_onset.f32"), BasicPitchConstants.OUTPUT_CONTOUR to f32("raw_contour.f32")))
        val tx = BasicPitchTranscriber(runner, batchSize = 2)
        val storeDir = kotlin.io.path.createTempDirectory("bpstore").toFile()
        // 1) interruzione dopo la terza chiamata
        val cancel = CancellationToken()
        var reports = 0
        val ctx = JobContext(ProgressSink { _, _ -> reports++; if (runner.calls >= 3) cancel.cancel() }, cancel)
        try {
            FileActivationStore(storeDir, resume = false).use { tx.infer(MemoryPcmSource(audio), it, ctx) }
            error("doveva essere annullato")
        } catch (_: CancelledException) {}
        val callsBefore = runner.calls
        // 2) ripresa dal checkpoint
        val store = FileActivationStore(storeDir, resume = true)
        assertTrue(store.frames > 0)
        val notes = tx.transcribe(MemoryPcmSource(audio), BasicPitchParams(), JobContext(), store)
        assertTrue("la ripresa non deve rieseguire tutte le finestre", runner.calls - callsBefore < 5)
        assertSameNotes(refNotes("notes_default.json"), notes, checkBends = true)
        // 3) decodifica a blocchi (brani lunghi): blocchi da 300 frame con sovrapposizione di 120
        val chunked = tx.decode(audio.frames.toLong(), store, BasicPitchParams(), chunkFrames = 300, overlapFrames = 120)
        val ref = refNotes("notes_default.json")
        val matched = chunked.count { c -> ref.any { r -> r.p == c.pitch && kotlin.math.abs(r.s - c.startSec) < 0.05 } }
        assertTrue("decodifica a blocchi: $matched/${ref.size} note corrispondenti", matched >= ref.size * 0.9)
        storeDir.deleteRecursively()
    }
}
