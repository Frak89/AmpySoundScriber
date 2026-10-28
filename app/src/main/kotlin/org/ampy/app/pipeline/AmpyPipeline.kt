package org.ampy.app.pipeline

import org.ampy.app.data.ProjectRepository
import org.ampy.audioengine.AudioBuffer
import org.ampy.audioengine.Mixer
import org.ampy.audioengine.PcmConvert
import org.ampy.audioengine.PcmSource
import org.ampy.audioengine.RawFloatFile
import org.ampy.audioengine.WavCodec
import org.ampy.composerai.ComposeRequest
import org.ampy.composerai.HierarchicalComposer
import org.ampy.core.AmpyLog
import org.ampy.core.ChordMark
import org.ampy.core.InferenceDevice
import org.ampy.core.InstrumentKind
import org.ampy.core.JobContext
import org.ampy.core.LogCategory
import org.ampy.core.NoteEvent
import org.ampy.core.Project
import org.ampy.core.ProjectCodec
import org.ampy.core.Section
import org.ampy.core.TempoChange
import org.ampy.core.TensorRunnerFactory
import org.ampy.core.Track
import org.ampy.core.Tuning
import org.ampy.core.sub
import org.ampy.instrumentai.InstrumentClassifier
import org.ampy.learninglab.LearningStore
import org.ampy.learninglab.ParameterTuner
import org.ampy.midilab.MidiReader
import org.ampy.midilab.MidiWriter
import org.ampy.midilab.PreviewSynth
import org.ampy.modelmanager.ModelRegistry
import org.ampy.modelmanager.ModelTask
import org.ampy.musicbrain.Chord
import org.ampy.musicbrain.ChordQuality
import org.ampy.musicbrain.ChordRecognizer
import org.ampy.musicbrain.ChordSegment
import org.ampy.musicbrain.Chromagram
import org.ampy.musicbrain.KeyDetector
import org.ampy.musicbrain.StructureAnalyzer
import org.ampy.rhythmlab.BeatTracker
import org.ampy.rhythmlab.DrumTranscriber
import org.ampy.rhythmlab.OnsetEnvelope
import org.ampy.rhythmlab.TempoEstimator
import org.ampy.scoreengine.MusicXmlReader
import org.ampy.scoreengine.MusicXmlWriter
import org.ampy.scoreengine.ScoreOptions
import org.ampy.scoreengine.TabGenerator
import org.ampy.separator.HpssSeparator
import org.ampy.separator.SourceSeparator
import org.ampy.separator.SpectralMaskModelSeparator
import org.ampy.separator.StemKind
import org.ampy.separator.WaveformModelSeparator
import org.ampy.transcriber.BasicPitchTranscriber
import org.ampy.transcriber.FileActivationStore
import org.ampy.transcriber.MonoPitchTranscriber
import org.ampy.transcriber.TranscriptionCheckpoint
import org.ampy.transcriber.TranscriptionProfile
import org.ampy.transcriber.TranscriptionQuality
import java.io.File
import java.io.OutputStream

/** Metodo di separazione scelto dall'utente: DSP integrato o un modello installato. */
data class SeparationMethod(val id: String, val label: String, val stems: List<String>, val note: String)

enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    MIDI("mid", "audio/midi", "MIDI"),
    MUSICXML("musicxml", "application/vnd.recordare.musicxml+xml", "MusicXML"),
    WAV_RECONSTRUCTION("wav", "audio/wav", "WAV (ricostruzione sonora)"),
    WAV_STEM("wav", "audio/wav", "WAV (stem separato)"),
    TAB_TEXT("txt", "text/plain", "Tablatura (testo)"),
    PROJECT("ampy.json", "application/json", "Progetto AmpySoundScriber"),
}

/**
 * Orchestrazione: Ascoltare → Separare → Riconoscere → Analizzare → Trascrivere → Correggere → Comporre → Esportare.
 * Nessuna dipendenza da Android: è compilata e collaudata anche sulla JVM (tools/verify_app_pipeline.sh).
 */
class AmpyPipeline(
    val repo: ProjectRepository,
    val models: ModelRegistry,
    val learning: LearningStore,
    private val runnerFactory: TensorRunnerFactory?,
    private val device: () -> InferenceDevice,
    private val segmentSec: () -> Double = { 20.0 },
    private val batchSize: () -> Int = { 4 },
) {
    // ------------------------------------------------------------ importazione
    fun newProjectFromSource(id: String, title: String, src: PcmSource): Project {
        val p = Project(id = id, title = title, sourceAudio = "source.f32", sampleRate = src.sampleRate, durationSec = src.durationSec,
            tracks = listOf(Track("mix", "Mix originale", InstrumentKind.OTHER, audioFile = "source.f32")),
            createdAtMs = System.currentTimeMillis(), modifiedAtMs = System.currentTimeMillis())
        return repo.save(p)
    }

    fun importMidi(bytes: ByteArray, title: String): Project {
        val m = MidiReader.read(bytes)
        val dur = m.tracks.flatMap { it.notes }.maxOfOrNull { it.endSec } ?: 0.0
        val key = m.key ?: KeyDetector.detect(m.tracks.filter { !it.instrument.isDrum }.flatMap { it.notes }).key
        return repo.save(Project(id = repo.newId(), title = title, durationSec = dur, tempo = m.tempo, key = key, timeSignature = m.timeSignature,
            tracks = m.tracks.map { withDefaultTuning(it) }, createdAtMs = System.currentTimeMillis()))
    }

    fun importMusicXml(bytes: ByteArray, title: String, compressed: Boolean): Project {
        val r = if (compressed) MusicXmlReader.readMxl(bytes) else MusicXmlReader.read(bytes.toString(Charsets.UTF_8))
        val dur = r.tracks.flatMap { it.notes }.maxOfOrNull { it.endSec } ?: 0.0
        return repo.save(Project(id = repo.newId(), title = r.title ?: title, durationSec = dur, tempo = r.tempo,
            key = r.key ?: KeyDetector.detect(r.tracks.flatMap { it.notes }).key, timeSignature = r.time ?: org.ampy.core.TimeSignature(),
            tracks = r.tracks.map { withDefaultTuning(it) }, createdAtMs = System.currentTimeMillis()))
    }

    fun importProject(text: String): Project {
        val p = ProjectCodec.decode(text)
        return repo.save(p.copy(id = repo.newId(), sourceAudio = null, tracks = p.tracks.map { it.copy(audioFile = null) }))
    }

    private fun withDefaultTuning(t: Track) = when (t.instrument) {
        InstrumentKind.BASS -> t.copy(tuning = t.tuning ?: Tuning.BASS_4)
        InstrumentKind.GUITAR, InstrumentKind.ELECTRIC_GUITAR -> t.copy(tuning = t.tuning ?: Tuning.GUITAR_STANDARD)
        else -> t
    }

    // ------------------------------------------------------------ separazione
    fun separationMethods(): List<SeparationMethod> {
        val list = arrayListOf(SeparationMethod("dsp-hpss", "DSP armonico/percussivo (sempre disponibile)", listOf("HARMONIC", "PERCUSSIVE", "LOW_BAND"),
            "Non isola i singoli strumenti: per voce/basso/batteria/altro installare un modello neurale."))
        for (m in models.list().filter { it.manifest.task == ModelTask.SEPARATION_WAVEFORM || it.manifest.task == ModelTask.SEPARATION_SPECTRAL_MASK }) {
            list.add(SeparationMethod(m.manifest.id, m.manifest.name + (if (m.manifest.stems.size > 4) " (multistrumento)" else " (4 sorgenti)"), m.manifest.stems,
                if (m.manifest.mobileVerified) "Verificato su dispositivo" else "Modello non ancora verificato su smartphone: prestazioni e memoria da misurare"))
        }
        return list
    }

    private fun separatorFor(id: String): Pair<SourceSeparator, Int?> {
        if (id == "dsp-hpss") return HpssSeparator(segmentSec = segmentSec()) to null
        val m = models.get(id) ?: throw IllegalArgumentException("Modello $id non installato")
        val factory = runnerFactory ?: throw IllegalStateException("Motore di inferenza non disponibile")
        val stems = m.manifest.stems.map { StemKind.valueOf(it) }
        return when (m.manifest.task) {
            ModelTask.SEPARATION_WAVEFORM -> WaveformModelSeparator(m.manifest.id, m.manifest.name, factory.open(m.modelFile.path, device()), stems, m.manifest.sampleRate,
                segmentSamples = m.manifest.params["segment"]?.toInt() ?: 343980, overlapSamples = m.manifest.params["overlap"]?.toInt() ?: 44100,
                inputName = m.manifest.inputName ?: "mix") to m.manifest.sampleRate
            ModelTask.SEPARATION_SPECTRAL_MASK -> SpectralMaskModelSeparator(m.manifest.id, m.manifest.name,
                stems.associateWith { k -> factory.open(File(m.dir, "${k.name.lowercase()}.onnx").let { if (it.exists()) it else m.modelFile }.path, device()) },
                nFft = m.manifest.params["nFft"]?.toInt() ?: 4096, hop = m.manifest.params["hop"]?.toInt() ?: 1024, segmentSec = segmentSec(),
                inputName = m.manifest.inputName ?: "mix", outputName = m.manifest.outputNames.firstOrNull() ?: "estimate") to m.manifest.sampleRate
            else -> throw IllegalArgumentException("Il modello $id non è un modello di separazione")
        }
    }

    private fun stemInstrument(k: StemKind) = when (k) {
        StemKind.VOCALS -> InstrumentKind.VOICE; StemKind.DRUMS, StemKind.PERCUSSIVE -> InstrumentKind.DRUMS; StemKind.BASS, StemKind.LOW_BAND -> InstrumentKind.BASS
        StemKind.GUITAR -> InstrumentKind.GUITAR; StemKind.PIANO -> InstrumentKind.PIANO; StemKind.WINDS -> InstrumentKind.BRASS
        else -> InstrumentKind.OTHER
    }

    fun separate(project: Project, methodId: String, ctx: JobContext): Project {
        val src = repo.openSource(project.id) ?: throw IllegalStateException("Il progetto non ha audio sorgente")
        src.use { source ->
            val (sep, rate) = separatorFor(methodId)
            val input: PcmSource = if (rate != null && rate != source.sampleRate || (rate != null && source.channelCount != 2)) {
                PcmConvert.toRate(source, rate!!, File(repo.cacheDir(project.id), "sep_input_$rate.f32"), channels = 2, ctx = JobContext(ctx.progress.sub(0.0, 0.1), ctx.cancel))
            } else source
            val tmpDir = File(repo.cacheDir(project.id), "sep-$methodId")
            val res = sep.separate(input, tmpDir, JobContext(ctx.progress.sub(0.1, 1.0), ctx.cancel))
            if (input !== source) input.close()
            val stemsDir = repo.stemsDir(project.id)
            val newTracks = res.stems.map { st ->
                val dest = File(stemsDir, "${methodId}_${st.kind.name.lowercase()}.f32")
                dest.delete(); st.file.renameTo(dest) || run { st.file.copyTo(dest, overwrite = true); st.file.delete() }
                repo.writePcmMeta(dest, st.sampleRate, st.channels)
                val warn = st.warnings + res.report.warnings.filter { it.contains(st.kind.label) }
                withDefaultTuning(Track("stem-${st.kind.name.lowercase()}", st.kind.label, stemInstrument(st.kind), audioFile = "stems/${dest.name}", warnings = warn))
            }
            tmpDir.deleteRecursively()
            val kept = project.tracks.filter { t -> newTracks.none { it.id == t.id } }.map { if (it.id == "mix") it.copy(muted = true) else it }
            AmpyLog.i(LogCategory.SEPARATION, "pipeline", "Separazione ${sep.displayName}: ${newTracks.size} stem; avvisi: ${res.report.warnings.size}")
            return project.copy(tracks = kept + newTracks, notes = (project.notes.filterNot { it.startsWith("Separazione:") } + res.report.warnings.map { "Separazione: $it" }).distinct())
        }
    }

    // ------------------------------------------------------------ trascrizione
    data class TranscribeOptions(val quality: TranscriptionQuality, val trackIds: Set<String>? = null)

    val hasBasicPitch: Boolean get() = runnerFactory != null && models.active(ModelTask.TRANSCRIPTION_BASIC_PITCH) != null

    /** Audio mono 22050 Hz della traccia (in cache, riutilizzato tra trascrizione e analisi). */
    private fun mono22k(project: Project, track: Track, ctx: JobContext): RawFloatFile {
        val rel = track.audioFile ?: throw IllegalStateException("Traccia senza audio")
        val cache = File(repo.cacheDir(project.id), "m22_" + rel.replace('/', '_'))
        val src = repo.openPcm(project.id, rel) ?: throw IllegalStateException("Audio mancante: $rel")
        src.use { s ->
            val expected = (s.frameCount * 22050.0 / s.sampleRate).toLong()
            if (cache.exists() && kotlin.math.abs(cache.length() / 4 - expected) <= 2) return RawFloatFile(cache, 22050, 1)
            return PcmConvert.toMono(s, 22050, cache, ctx)
        }
    }

    fun transcribe(project: Project, opt: TranscribeOptions, ctx: JobContext): Project {
        val targets = project.tracks.filter { it.audioFile != null && (if (opt.trackIds != null) it.id in opt.trackIds else !it.muted) }
        if (targets.isEmpty()) throw IllegalStateException("Nessuna traccia audio da trascrivere")
        val tuner = ParameterTuner(learning)
        val bpModel = models.active(ModelTask.TRANSCRIPTION_BASIC_PITCH)
        val runner = if (bpModel != null && runnerFactory != null && targets.any { !it.instrument.isDrum }) runnerFactory.open(bpModel.modelFile.path, device()) else null
        val updated = HashMap<String, Track>()
        try {
            targets.forEachIndexed { ti, track ->
                val part = ctx.progress.sub(ti.toDouble() / targets.size, (ti + 1.0) / targets.size)
                ctx.cancel.throwIfCancelled()
                val audio = mono22k(project, track, JobContext(part.sub(0.0, 0.15), ctx.cancel))
                audio.use { a ->
                    val notes: List<NoteEvent>
                    val warnings = ArrayList(track.warnings.filterNot { it.startsWith("Trascrizione:") })
                    when {
                        track.instrument.isDrum -> notes = drumsChunked(a, JobContext(part.sub(0.15, 1.0), ctx.cancel))
                        runner != null -> {
                            val profile = TranscriptionProfile.forInstrument(track.instrument)
                            val params = tuner.load(profile.name, profile.params(opt.quality))
                            val storeDir = File(repo.cacheDir(project.id), "bp_${track.id}")
                            val ckFile = File(storeDir, "checkpoint.json")
                            val ck = TranscriptionCheckpoint(track.audioFile!!, a.frameCount, bpModel!!.manifest.sha256 ?: "")
                            val resume = ckFile.exists() && runCatching { TranscriptionCheckpoint.parse(ckFile.readText()) == ck }.getOrDefault(false)
                            val store = FileActivationStore(storeDir, resume)
                            ckFile.writeText(ck.toJson())
                            val bp = BasicPitchTranscriber(runner, batchSize())
                            bp.infer(a, store, JobContext(part.sub(0.15, 0.9), ctx.cancel))
                            notes = bp.decode(a.frameCount, store, params, JobContext(part.sub(0.9, 1.0), ctx.cancel))
                        }
                        TranscriptionProfile.forInstrument(track.instrument) in setOf(TranscriptionProfile.VOICE, TranscriptionProfile.BASS, TranscriptionProfile.WIND) -> {
                            notes = monoChunked(a, track.instrument, JobContext(part.sub(0.15, 1.0), ctx.cancel))
                            warnings.add("Trascrizione: modello Basic Pitch non disponibile, usato il tracciatore monofonico YIN")
                        }
                        else -> {
                            notes = monoChunked(a, track.instrument, JobContext(part.sub(0.15, 1.0), ctx.cancel))
                            warnings.add("Trascrizione: senza modello polifonico la traccia è stata trattata come monofonica (risultato parziale)")
                        }
                    }
                    var t = track.copy(notes = notes, warnings = warnings)
                    if (t.instrument == InstrumentKind.OTHER && t.id != "mix" && notes.size > 10) {
                        val guess = InstrumentClassifier.classify(InstrumentClassifier.features(a.readMono(0, minOf(a.frameCount, 22050L * 30).toInt()), 22050, notes))
                        if (guess.confidence > 0.3) t = t.copy(instrument = guess.kind, warnings = t.warnings + "Strumento stimato (euristico, confidenza ${"%.0f".format(guess.confidence * 100)}%): ${InstrumentClassifier.displayName(guess.kind)}")
                    }
                    updated[track.id] = withDefaultTuning(t)
                    AmpyLog.i(LogCategory.TRANSCRIPTION, "pipeline", "${track.name}: ${notes.size} note")
                }
            }
        } finally { runner?.close() }
        return project.copy(tracks = project.tracks.map { updated[it.id] ?: it })
    }

    private fun drumsChunked(a: RawFloatFile, ctx: JobContext, chunkSec: Int = 60): List<NoteEvent> {
        val out = ArrayList<NoteEvent>(); val n = 22050L * chunkSec; var pos = 0L
        while (pos < a.frameCount) {
            ctx.cancel.throwIfCancelled()
            val x = a.readMono(pos, (n + 22050).toInt())
            val off = pos / 22050.0
            out.addAll(DrumTranscriber.transcribe(x, 22050).filter { it.startSec < chunkSec || pos + n >= a.frameCount }.map { it.copy(startSec = it.startSec + off, endSec = it.endSec + off) })
            pos += n
            ctx.progress.report(pos.toDouble() / a.frameCount, "Trascrizione batteria")
        }
        return out.sortedBy { it.startSec }
    }

    private fun monoChunked(a: RawFloatFile, kind: InstrumentKind, ctx: JobContext, chunkSec: Int = 120): List<NoteEvent> {
        val (lo, hi) = when (kind) { InstrumentKind.BASS -> 30.0 to 420.0; InstrumentKind.VOICE -> 70.0 to 1200.0; else -> 50.0 to 2000.0 }
        val tx = MonoPitchTranscriber(22050, minHz = lo, maxHz = hi)
        val out = ArrayList<NoteEvent>(); val n = 22050L * chunkSec; var pos = 0L
        while (pos < a.frameCount) {
            ctx.cancel.throwIfCancelled()
            val off = pos / 22050.0
            val notes = tx.transcribe(a.readMono(pos, (n + 22050).toInt()))
            out.addAll(notes.filter { it.startSec < chunkSec || pos + n >= a.frameCount }.map { it.copy(startSec = it.startSec + off, endSec = it.endSec + off) })
            pos += n
            ctx.progress.report(pos.toDouble() / a.frameCount, "Tracciamento altezza (YIN)")
        }
        return out
    }

    // ------------------------------------------------------------ analisi
    fun analyze(project: Project, ctx: JobContext): Project {
        var p = project
        val pitched = p.tracks.filter { !it.instrument.isDrum && !it.muted }.flatMap { it.notes }.ifEmpty { p.tracks.filter { !it.instrument.isDrum }.flatMap { it.notes } }
        // tempo e battiti dall'audio sorgente (se presente) altrimenti dagli onset delle note
        var beats: List<Double> = emptyList()
        val mixTrack = p.tracks.firstOrNull { it.id == "mix" && it.audioFile != null }
        if (mixTrack != null) {
            mono22k(p, mixTrack, JobContext(ctx.progress.sub(0.0, 0.3), ctx.cancel)).use { a ->
                val env = envelopeChunked(a, ctx)
                val tempo = TempoEstimator.tempoCurve(env)
                beats = BeatTracker.track(env, tempo.first().bpm)
                p = p.copy(tempo = tempo)
                AmpyLog.i(LogCategory.TRANSCRIPTION, "analysis", "Tempo stimato: ${tempo.joinToString { "%.1f BPM @ %.0fs".format(it.bpm, it.timeSec) }}")
            }
        }
        ctx.progress.report(0.5, "Tonalità e accordi")
        if (pitched.isNotEmpty()) {
            val key = KeyDetector.detect(pitched).key
            val dur = maxOf(p.durationSec, pitched.maxOf { it.endSec })
            val chroma = Chromagram.fromNotes(pitched, 0.5, dur)
            val keyNotes = KeyDetector.keyChanges(chroma).drop(1).map { (t, k) -> "Cambio di tonalità a ${"%d:%02d".format((t / 60).toInt(), (t % 60).toInt())} → ${k.name}" }
            val bpm = p.initialBpm
            val grid = if (beats.size > 8) beats.filterIndexed { i, _ -> i % 2 == 0 } else generateSequence(0.0) { it + 120.0 / bpm }.takeWhile { it <= dur }.toList()
            val chords = ChordRecognizer.recognize(pitched, grid + dur).filter { it.chord != null && it.confidence > 0.05 }
            val barStarts = if (beats.size > 16) beats.filterIndexed { i, _ -> i % p.timeSignature.numerator == 0 } else generateSequence(0.0) { it + 240.0 / bpm }.takeWhile { it < dur }.toList()
            val barChroma = barStarts.indices.map { i -> Chromagram.fromNotes(pitched.filter { it.endSec > barStarts[i] && it.startSec < barStarts.getOrElse(i + 1) { dur } }, 1000.0, dur).sum() }
            val sections = StructureAnalyzer.analyze(barChroma, barStarts).map { Section("Sezione ${it.label}", it.startSec, it.endSec) }
            p = p.copy(key = key, sections = sections,
                chords = chords.map { seg -> val ch = seg.chord!!; ChordMark(seg.startSec, seg.endSec, ch.root, ch.quality.name, ch.bass, seg.confidence) },
                notes = (p.notes.filterNot { it.startsWith("Cambio di tonalità") } + keyNotes))
        }
        ctx.progress.report(1.0, "Analisi completata")
        return p.copy(durationSec = maxOf(p.durationSec, p.tracks.flatMap { it.notes }.maxOfOrNull { it.endSec } ?: 0.0))
    }

    private fun envelopeChunked(a: RawFloatFile, ctx: JobContext): OnsetEnvelope {
        val hop = 512; val nFft = 2048
        val chunk = 22050 * 60 / hop * hop
        val vals = ArrayList<Float>(); var pos = 0L
        while (pos < a.frameCount) {
            ctx.cancel.throwIfCancelled()
            val x = a.readMono(pos, chunk + nFft)
            val env = OnsetEnvelope.compute(x, 22050, nFft, hop)
            vals.addAll(env.values.take(chunk / hop).toList())
            pos += chunk
        }
        return OnsetEnvelope(vals.toFloatArray(), 22050.0 / hop)
    }

    // ------------------------------------------------------------ composizione
    fun compose(req: ComposeRequest, ctx: JobContext): Project {
        val c = HierarchicalComposer(req)
        val p = c.compose(ctx)
        val chords = c.chordSegments(c.planSections()).map { seg -> val ch = seg.chord!!; ChordMark(seg.startSec, seg.endSec, ch.root, ch.quality.name, ch.bass, 1.0) }
        return repo.save(p.copy(id = repo.newId(), chords = chords, tracks = p.tracks.map { withDefaultTuning(it) }))
    }

    // ------------------------------------------------------------ ricostruzione sonora ed esportazione
    /** Sintetizza la traccia MIDI in render/<id>.f32 a blocchi da 30 s (memoria costante). */
    fun renderTrack(project: Project, track: Track, ctx: JobContext = JobContext(), sampleRate: Int = 44100): RawFloatFile {
        val out = File(repo.renderDir(project.id), "${track.id}.f32")
        val total = ((maxOf(project.durationSec, track.notes.maxOfOrNull { it.endSec } ?: 0.0) + 1.5) * sampleRate).toLong()
        RawFloatFile.Writer(out, 1).use { w ->
            var pos = 0L; val block = sampleRate * 30
            while (pos < total) {
                val n = minOf(block.toLong(), total - pos).toInt()
                w.writeInterleaved(PreviewSynth.renderRange(track, sampleRate, pos, n, ctx.cancel))
                pos += n
                ctx.progress.report(pos.toDouble() / total, "Sintesi ${track.name}")
            }
        }
        repo.writePcmMeta(out, sampleRate, 1)
        return RawFloatFile(out, sampleRate, 1)
    }

    fun chordSegments(p: Project): List<ChordSegment> = p.chords.map { ChordSegment(it.startSec, it.endSec, Chord(it.root, runCatching { ChordQuality.valueOf(it.quality) }.getOrDefault(ChordQuality.MAJOR), it.bass), it.confidence) }

    fun musicXml(p: Project, tracks: List<Track> = p.tracks.filter { it.notes.isNotEmpty() }, withTab: Boolean = true): String =
        MusicXmlWriter.write(p, tracks, ScoreOptions(chords = chordSegments(p),
            tabTracks = if (withTab) tracks.filter { it.instrument in setOf(InstrumentKind.GUITAR, InstrumentKind.ELECTRIC_GUITAR, InstrumentKind.BASS) }.map { it.id }.toSet() else emptySet()))

    fun export(p: Project, track: Track?, format: ExportFormat, out: OutputStream, ctx: JobContext = JobContext()) {
        val tracks = if (track != null) listOf(track) else p.tracks.filter { it.notes.isNotEmpty() }
        when (format) {
            ExportFormat.MIDI -> out.write(MidiWriter.write(p, tracks))
            ExportFormat.MUSICXML -> out.write(musicXml(p, tracks).toByteArray(Charsets.UTF_8))
            ExportFormat.TAB_TEXT -> {
                val sb = StringBuilder()
                // traccia singola: sempre (accordatura della traccia o chitarra standard); progetto intero: solo chitarre e bassi
                for (t in tracks.filter { track != null || it.tuning != null || it.instrument == InstrumentKind.BASS || it.instrument == InstrumentKind.GUITAR || it.instrument == InstrumentKind.ELECTRIC_GUITAR }.filter { !it.instrument.isDrum }) {
                    val tuning = t.tuning ?: if (t.instrument == InstrumentKind.BASS) Tuning.BASS_4 else Tuning.GUITAR_STANDARD
                    val gen = TabGenerator(tuning)
                    sb.append("== ").append(t.name).append(" ==\n").append(gen.renderAscii(gen.assign(t.notes), stepSec = 60.0 / p.initialBpm / 4)).append('\n')
                }
                out.write(sb.toString().ifEmpty { "Nessuna traccia di chitarra o basso.\n" }.toByteArray())
            }
            ExportFormat.WAV_RECONSTRUCTION -> {
                val renders = tracks.mapIndexed { i, t -> renderTrack(p, t, JobContext(ctx.progress.sub(i.toDouble() / tracks.size * 0.8, (i + 1.0) / tracks.size * 0.8), ctx.cancel)) }
                val tmp = File(repo.cacheDir(p.id), "mixdown.f32")
                val frames = renders.maxOf { it.frameCount }
                RawFloatFile.Writer(tmp, 2).use { w ->
                    var pos = 0L; val block = 65536
                    while (pos < frames) {
                        val n = minOf(block.toLong(), frames - pos).toInt()
                        val chans = renders.mapIndexed { i, r -> val m = r.readMono(pos, n).copyOf(n); val t = tracks[i]; Mixer.Channel(m, m, t.volume, t.pan, t.muted && track == null, t.solo && track == null) }
                        val mix = Mixer.mix(chans, n)
                        val inter = FloatArray(n * 2); for (k in 0 until n) { inter[2 * k] = kotlin.math.tanh(mix[0][k]); inter[2 * k + 1] = kotlin.math.tanh(mix[1][k]) }
                        w.writeInterleaved(inter, n); pos += n
                    }
                }
                renders.forEach { it.close() }
                writeWav(RawFloatFile(tmp, renders.first().sampleRate, 2), out); tmp.delete()
            }
            ExportFormat.WAV_STEM -> {
                val t = track ?: throw IllegalArgumentException("Selezionare una traccia")
                val src = repo.openPcm(p.id, t.audioFile ?: throw IllegalArgumentException("La traccia non ha audio"))!!
                writeWav(src, out)
            }
            ExportFormat.PROJECT -> out.write(ProjectCodec.encode(p).toByteArray())
        }
        out.flush()
    }

    private fun writeWav(src: RawFloatFile, out: OutputStream) {
        val tmp = File.createTempFile("ampy", ".wav", repo.cacheDir("tmp"))
        src.use { WavCodec.write16Streaming(tmp, it) }
        tmp.inputStream().use { it.copyTo(out) }
        tmp.delete()
    }

    /** Mixdown in memoria di breve durata (anteprima). */
    fun previewBuffer(p: Project, seconds: Double): AudioBuffer = PreviewSynth.renderMix(p.tracks, 22050, minOf(seconds, p.durationSec))

    fun tempoText(t: List<TempoChange>) = t.joinToString { "%.1f".format(it.bpm) }
}
