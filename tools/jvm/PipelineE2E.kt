// Collaudo end-to-end della pipeline dell'app sulla JVM (senza Android).
// Compilato ed eseguito da tools/verify_app_pipeline.sh.
import org.ampy.app.data.ProjectRepository
import org.ampy.app.pipeline.AmpyPipeline
import org.ampy.app.pipeline.ExportFormat
import org.ampy.audioengine.RawFloatFile
import org.ampy.audioengine.WavCodec
import org.ampy.composerai.ComposeRequest
import org.ampy.composerai.Genre
import org.ampy.core.FloatTensor
import org.ampy.core.InferenceDevice
import org.ampy.core.JobContext
import org.ampy.core.Json
import org.ampy.core.ProgressSink
import org.ampy.core.TensorRunner
import org.ampy.core.TensorRunnerFactory
import org.ampy.learninglab.LearningStore
import org.ampy.learninglab.TranscriptionMetrics
import org.ampy.midilab.MidiReader
import org.ampy.modelmanager.ModelManifest
import org.ampy.modelmanager.ModelRegistry
import org.ampy.transcriber.TranscriptionQuality
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PythonOrtRunner(python: String, server: String, model: String, private val work: File) : TensorRunner {
    private val proc = ProcessBuilder(python, server, model).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val reader = BufferedReader(InputStreamReader(proc.inputStream))
    private val hello = Json.parse(reader.readLine()) as Json.Obj
    override val inputNames = hello.arr("inputs")!!.items.map { (it as Json.Str).value }
    override val outputNames = hello.arr("outputs")!!.items.map { (it as Json.Str).value }
    override val activeDevice = InferenceDevice.CPU
    var calls = 0
    override fun run(inputs: Map<String, FloatTensor>): Map<String, FloatTensor> {
        calls++
        work.mkdirs()
        val req = Json.Obj()
        val ins = Json.Obj()
        for ((k, t) in inputs) {
            val f = File(work, "in_${k.replace(':', '_')}.f32")
            val bb = ByteBuffer.allocate(t.size * 4).order(ByteOrder.LITTLE_ENDIAN); bb.asFloatBuffer().put(t.data); f.writeBytes(bb.array())
            ins[k] = org.ampy.core.jsonObj("shape" to t.shape.toList(), "file" to f.path)
        }
        req["inputs"] = ins; req["outdir"] = Json.Str(work.path)
        proc.outputStream.write((req.encode() + "\n").toByteArray()); proc.outputStream.flush()
        val resp = (Json.parse(reader.readLine()) as Json.Obj).obj("outputs")!!
        return resp.map.mapValues { (_, v) ->
            val o = v as Json.Obj
            val fb = ByteBuffer.wrap(File(o.str("file")!!).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatTensor(o.arr("shape")!!.items.map { (it as Json.Num).value.toInt() }.toIntArray(), FloatArray(fb.remaining()).also { fb.get(it) })
        }
    }
    override fun close() { proc.outputStream.close(); proc.waitFor() }
}

fun check(cond: Boolean, msg: String) { if (!cond) { System.err.println("FALLITO: $msg"); kotlin.system.exitProcess(1) } else println("ok  $msg") }

fun main(args: Array<String>) {
    val (python, server, root) = args
    val testdata = File(root, "testdata")
    val work = kotlin.io.path.createTempDirectory("ampy-e2e").toFile()
    val repo = ProjectRepository(File(work, "projects"))
    val registry = ModelRegistry(File(work, "models"))
    val assetDir = File(root, "app/src/main/assets/models/basic-pitch")
    val manifest = ModelManifest.parse(File(assetDir, "manifest.json").readText())
    File(assetDir, manifest.file).inputStream().use { registry.install(manifest, it) }  // verifica SHA-256 come in app
    var runner: PythonOrtRunner? = null
    val factory = TensorRunnerFactory { path, _ -> PythonOrtRunner(python, server, path, File(work, "ort")).also { runner = it } }
    val pipeline = AmpyPipeline(repo, registry, LearningStore(File(work, "learning")), factory, { InferenceDevice.CPU })

    // 1) importazione: il brano di prova (come farebbe il decoder Android) diventa source.f32
    val wav = WavCodec.read(File(testdata, "bp/input.wav"))
    val id = repo.newId()
    repo.dir(id).mkdirs()
    RawFloatFile.Writer(repo.sourceFile(id), wav.channelCount).use { w ->
        val inter = FloatArray(wav.frames * wav.channelCount); for (i in 0 until wav.frames) for (c in 0 until wav.channelCount) inter[i * wav.channelCount + c] = wav.channels[c][i]
        w.writeInterleaved(inter, wav.frames)
    }
    repo.writeSourceMeta(id, wav.sampleRate, wav.channelCount, "input.wav", "audio/wav")
    var p = pipeline.newProjectFromSource(id, "Brano di prova", repo.openSource(id)!!)
    check(p.tracks.size == 1, "progetto creato con la traccia del mix")

    // 2) trascrizione del mix con il modello Basic Pitch reale
    var lastProgress = -1.0; var monotonic = true
    val ctx = JobContext(ProgressSink { f, _ -> if (f + 1e-9 < lastProgress) monotonic = false; lastProgress = f })
    val t0 = System.nanoTime()
    p = pipeline.transcribe(p, AmpyPipeline.TranscribeOptions(TranscriptionQuality.QUALITY), ctx)
    val ms = (System.nanoTime() - t0) / 1e6
    check(monotonic && lastProgress > 0.99, "avanzamento reale e monotono (ultimo ${"%.2f".format(lastProgress)})")
    val ref = MidiReader.read(File(testdata, "bp/reference.mid").readBytes()).tracks.flatMap { it.notes }
    val notes = p.tracks.first { it.id == "mix" }.notes
    val rep = TranscriptionMetrics.evaluate(ref, notes, runtimeMs = ms)
    println(rep.toText())
    check(rep.noteOnset.f1 > 0.7, "Note F1 della pipeline completa > 0.7 (misurata ${"%.3f".format(rep.noteOnset.f1)})")
    // confronto con l'output Python di riferimento (stesso modello, ricampionamento identico perché l'audio è già a 22050 Hz)
    val pyCount = (Json.parse(File(testdata, "bp/notes_default.json").readText()) as Json.Arr).items.size
    check(notes.size == pyCount, "stesso numero di note dell'implementazione Python (${notes.size} vs $pyCount)")

    // 3) separazione DSP + analisi
    p = pipeline.separate(p, "dsp-hpss", JobContext())
    check(p.tracks.any { it.id == "stem-harmonic" } && p.tracks.first { it.id == "mix" }.muted, "stem creati e mix silenziato")
    p = pipeline.analyze(p, JobContext())
    println("Tonalità: ${p.key.name}; tempo: ${pipeline.tempoText(p.tempo)} BPM; accordi: ${p.chords.take(8).joinToString { org.ampy.musicbrain.Chord(it.root, org.ampy.musicbrain.ChordQuality.valueOf(it.quality), it.bass).symbol }}")
    check(p.key.tonicPitchClass == 0 && !p.key.isMinor, "tonalità Do maggiore riconosciuta")
    check(kotlin.math.abs(p.initialBpm - 100.0) < 5 || kotlin.math.abs(p.initialBpm - 150.0) < 5 || kotlin.math.abs(p.initialBpm - 75.0) < 4, "tempo plausibile (riferimento 100 BPM): ${p.initialBpm}")
    check(p.chords.isNotEmpty(), "accordi riconosciuti")

    // 4) salvataggio/ricarica e autosalvataggio recuperabile
    p = repo.save(p)
    repo.autosave(p.copy(title = "Modificato"))
    val loaded = repo.load(p.id)
    check(loaded.project.tracks.size == p.tracks.size && loaded.recovered?.title == "Modificato", "salvataggio e recupero autosave")

    // 5) esportazioni
    val outDir = File(testdata, "out").apply { mkdirs() }
    for (f in listOf(ExportFormat.MIDI, ExportFormat.MUSICXML, ExportFormat.TAB_TEXT, ExportFormat.WAV_RECONSTRUCTION, ExportFormat.PROJECT)) {
        val file = File(outDir, "e2e.${f.ext}")
        file.outputStream().use { pipeline.export(p, null, f, it) }
        check(file.length() > (if (f == ExportFormat.TAB_TEXT) 10 else 100), "export ${f.label}: ${file.length()} byte")
    }
    val tab = File(outDir, "e2e_tab_mix.txt")
    tab.outputStream().use { pipeline.export(p, p.tracks.first { it.id == "mix" }, ExportFormat.TAB_TEXT, it) }
    check(tab.readText().contains("E |"), "tablatura della singola traccia")
    val stemWav = File(outDir, "e2e_stem.wav")
    stemWav.outputStream().use { pipeline.export(p, p.tracks.first { it.id == "stem-percussive" }, ExportFormat.WAV_STEM, it) }
    check(WavCodec.read(stemWav).frames == wav.frames, "WAV dello stem con durata originale")

    // 6) composizione completa
    val comp = pipeline.compose(ComposeRequest(genre = Genre.PROG_ROCK, durationSec = 300.0, seed = 21), JobContext())
    check(comp.durationSec in 270.0..330.0 && comp.chords.isNotEmpty(), "composizione di ${"%.0f".format(comp.durationSec)} s con ${comp.tracks.size} tracce")
    File(outDir, "composed_prog.musicxml").writeText(pipeline.musicXml(comp))
    println("chiamate al modello: ${runner?.calls}")
    work.deleteRecursively()
    println("E2E COMPLETATO")
}
