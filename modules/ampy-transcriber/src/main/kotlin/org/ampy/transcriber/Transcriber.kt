package org.ampy.transcriber

import org.ampy.audioengine.PcmSource
import org.ampy.audioengine.RawFloatFile
import org.ampy.core.AmpyLog
import org.ampy.core.FloatTensor
import org.ampy.core.InstrumentKind
import org.ampy.core.JobContext
import org.ampy.core.Json
import org.ampy.core.LogCategory
import org.ampy.core.ModuleInfo
import org.ampy.core.NoteEvent
import org.ampy.core.TensorRunner
import org.ampy.core.jsonObj
import org.ampy.core.sub
import java.io.File

object TranscriberModule {
    val info = ModuleInfo("AmpyTranscriber", "0.1.0", "Trascrizione polifonica (Basic Pitch ONNX) e monofonica DSP (YIN)")
}

enum class TranscriptionQuality { FAST, QUALITY }

/** Profili per strumento: limitano le frequenze e adattano le soglie (riduce falsi positivi su stem separati). */
enum class TranscriptionProfile(val minHz: Double?, val maxHz: Double?, val onset: Double, val frame: Double, val minNoteMs: Double) {
    GENERIC(null, null, 0.5, 0.3, 127.7),
    PIANO(27.0, 4200.0, 0.5, 0.3, 80.0),
    GUITAR(75.0, 1400.0, 0.5, 0.3, 90.0),
    BASS(30.0, 420.0, 0.45, 0.3, 100.0),
    VOICE(75.0, 1200.0, 0.5, 0.35, 120.0),
    WIND(100.0, 2200.0, 0.5, 0.3, 110.0);

    fun params(q: TranscriptionQuality, base: BasicPitchParams = BasicPitchParams()) = base.copy(
        onsetThreshold = onset, frameThreshold = frame, minimumNoteLengthMs = minNoteMs,
        minimumFrequency = minHz, maximumFrequency = maxHz, includePitchBends = q == TranscriptionQuality.QUALITY,
    )

    companion object {
        fun forInstrument(k: InstrumentKind) = when (k) {
            InstrumentKind.PIANO, InstrumentKind.ELECTRIC_PIANO, InstrumentKind.ORGAN -> PIANO
            InstrumentKind.GUITAR, InstrumentKind.ELECTRIC_GUITAR -> GUITAR
            InstrumentKind.BASS -> BASS
            InstrumentKind.VOICE -> VOICE
            InstrumentKind.BRASS, InstrumentKind.WOODWIND -> WIND
            else -> GENERIC
        }
    }
}

/** Archivio delle attivazioni del modello: in memoria o su disco (per brani lunghi e checkpoint). */
interface ActivationStore : AutoCloseable {
    val frames: Long
    fun append(note: FloatArray, onset: FloatArray, contour: FloatArray)
    fun read(from: Int, to: Int): Triple<Activations, Activations, Activations>
}

class MemoryActivationStore : ActivationStore {
    private val n = ArrayList<FloatArray>(); private val o = ArrayList<FloatArray>(); private val c = ArrayList<FloatArray>()
    override var frames = 0L; private set
    override fun append(note: FloatArray, onset: FloatArray, contour: FloatArray) { n.add(note); o.add(onset); c.add(contour); frames += note.size / BasicPitchConstants.N_NOTES }
    private fun cat(l: List<FloatArray>, bins: Int, from: Int, to: Int): Activations {
        val all = FloatArray(l.sumOf { it.size }); var p = 0
        for (a in l) { a.copyInto(all, p); p += a.size }
        return Activations(to - from, bins, all.copyOfRange(from * bins, to * bins))
    }
    override fun read(from: Int, to: Int) = Triple(cat(n, 88, from, to), cat(o, 88, from, to), cat(c, 264, from, to))
    override fun close() {}
}

/** Archivio su disco riprendibile: i file .f32 crescono finestra dopo finestra. */
class FileActivationStore(val dir: File, resume: Boolean) : ActivationStore {
    private val nf = File(dir, "note.f32"); private val of = File(dir, "onset.f32"); private val cf = File(dir, "contour.f32")
    init {
        dir.mkdirs()
        if (!resume) { nf.delete(); of.delete(); cf.delete() }
        // allinea i tre file al minimo numero di frame completi (dopo un'interruzione)
        val complete = minOf(nf.length() / (88 * 4), of.length() / (88 * 4), cf.length() / (264 * 4))
        val aligned = complete - complete % BasicPitchConstants.FRAMES_KEPT_PER_WINDOW
        for ((f, b) in listOf(nf to 88, of to 88, cf to 264)) if (f.exists()) java.io.RandomAccessFile(f, "rw").use { it.setLength(aligned * b * 4) }
    }
    private fun appendFloats(f: File, data: FloatArray) {
        val bb = java.nio.ByteBuffer.allocate(data.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(data)
        java.io.FileOutputStream(f, true).use { it.write(bb.array()) }
    }
    override val frames: Long get() = nf.length() / (88 * 4)
    override fun append(note: FloatArray, onset: FloatArray, contour: FloatArray) {
        // il contour per ultimo: un frame è "completo" solo quando tutti e tre i file lo contengono
        appendFloats(nf, note); appendFloats(of, onset); appendFloats(cf, contour)
    }
    override fun read(from: Int, to: Int): Triple<Activations, Activations, Activations> {
        fun rd(f: File, bins: Int): Activations = RawFloatFile(f, 1, bins).use { s ->
            val out = FloatArray((to - from) * bins); s.read(from.toLong(), to - from, out); Activations(to - from, bins, out)
        }
        return Triple(rd(nf, 88), rd(of, 88), rd(cf, 264))
    }
    override fun close() {}
}

/**
 * Pipeline Basic Pitch completa: finestre -> modello (via [TensorRunner]) -> unwrap -> note.
 * L'audio deve essere mono a 22050 Hz (usare PcmConvert.toMono).
 */
class BasicPitchTranscriber(private val runner: TensorRunner, private val batchSize: Int = 4) {

    /** Esegue il modello su tutte le finestre mancanti. Riprende da [store].frames (checkpoint). */
    fun infer(src: PcmSource, store: ActivationStore, ctx: JobContext) {
        require(src.sampleRate == BasicPitchConstants.SAMPLE_RATE && src.channelCount == 1) { "Basic Pitch richiede audio mono a 22050 Hz" }
        val c = BasicPitchConstants
        val nWin = BasicPitchFraming.windowCount(src.frameCount)
        var w = (store.frames / c.FRAMES_KEPT_PER_WINDOW).toInt()
        if (w > 0) AmpyLog.i(LogCategory.TRANSCRIPTION, "basicpitch", "Ripresa dal checkpoint: finestra $w/$nWin")
        val t0 = System.nanoTime()
        while (w < nWin) {
            ctx.cancel.throwIfCancelled()
            val b = minOf(batchSize, nWin - w)
            val input = FloatArray(b * c.AUDIO_N_SAMPLES)
            for (k in 0 until b) BasicPitchFraming.window(w + k, src.frameCount) { s, n -> src.readMono(s, n) }.copyInto(input, k * c.AUDIO_N_SAMPLES)
            val out = runner.run(mapOf(c.INPUT_NAME to FloatTensor(intArrayOf(b, c.AUDIO_N_SAMPLES, 1), input)))
            val note = out[c.OUTPUT_NOTE] ?: error("uscita ${c.OUTPUT_NOTE} mancante")
            val onset = out[c.OUTPUT_ONSET] ?: error("uscita ${c.OUTPUT_ONSET} mancante")
            val contour = out[c.OUTPUT_CONTOUR] ?: error("uscita ${c.OUTPUT_CONTOUR} mancante")
            check(note.shape.contentEquals(intArrayOf(b, c.ANNOT_N_FRAMES, 88))) { "forma note inattesa ${note.shape.contentToString()}" }
            for (k in 0 until b) {
                fun win(t: FloatTensor, bins: Int) = BasicPitchFraming.trimWindow(t.data.copyOfRange(k * c.ANNOT_N_FRAMES * bins, (k + 1) * c.ANNOT_N_FRAMES * bins), bins)
                store.append(win(note, 88), win(onset, 88), win(contour, 264))
            }
            w += b
            ctx.progress.report(w.toDouble() / nWin, "Inferenza Basic Pitch: finestra $w/$nWin")
        }
        AmpyLog.i(LogCategory.TIMING, "basicpitch", "Inferenza ${"%.2f".format((System.nanoTime() - t0) / 1e9)} s per $nWin finestre (${runner.activeDevice})")
    }

    /**
     * Decodifica le attivazioni in note. Per brani lunghi lavora per blocchi sovrapposti
     * ([chunkFrames], default 5 minuti) mantenendo solo le note che iniziano nella zona centrale del blocco.
     */
    fun decode(totalSamples: Long, store: ActivationStore, params: BasicPitchParams, ctx: JobContext = JobContext(),
               chunkFrames: Int = BasicPitchConstants.ANNOTATIONS_FPS * 300, overlapFrames: Int = BasicPitchConstants.ANNOTATIONS_FPS * 4): List<NoteEvent> {
        val nOut = minOf(BasicPitchFraming.outputFrames(totalSamples).toLong(), store.frames).toInt()
        if (nOut <= 2) return emptyList()
        val result = ArrayList<NoteEvent>()
        var a = 0
        while (a < nOut) {
            ctx.cancel.throwIfCancelled()
            val b = minOf(nOut, a + chunkFrames)
            val readFrom = maxOf(0, a - overlapFrames); val readTo = minOf(nOut, b + overlapFrames)
            val (note, onset, contour) = store.read(readFrom, readTo)
            var fn = BasicPitchNotes.outputToNotesPolyphonic(note, onset, params)
            if (params.includePitchBends) fn = BasicPitchNotes.pitchBends(contour, fn)
            val single = readFrom == 0 && readTo == nOut
            val kept = if (single) fn else fn.filter { it.startFrame + readFrom in a until b }
            result.addAll(BasicPitchNotes.toNoteEvents(kept, frameOffset = readFrom))
            ctx.progress.report(b.toDouble() / nOut, "Decodifica note")
            a = b
        }
        return result.sortedWith(compareBy({ it.startSec }, { it.pitch }))
    }

    fun transcribe(src: PcmSource, params: BasicPitchParams, ctx: JobContext, store: ActivationStore = MemoryActivationStore()): List<NoteEvent> {
        infer(src, store, JobContext(ctx.progress.sub(0.0, 0.9), ctx.cancel))
        return decode(src.frameCount, store, params, JobContext(ctx.progress.sub(0.9, 1.0), ctx.cancel))
    }
}

/** Riepilogo per checkpoint su disco (consente di riprendere un job interrotto). */
data class TranscriptionCheckpoint(val sourceId: String, val totalSamples: Long, val modelSha256: String) {
    fun toJson() = jsonObj("sourceId" to sourceId, "totalSamples" to totalSamples, "model" to modelSha256).encode()
    companion object {
        fun parse(s: String) = (Json.parse(s) as Json.Obj).let { TranscriptionCheckpoint(it.str("sourceId")!!, it.long("totalSamples")!!, it.str("model")!!) }
    }
}
