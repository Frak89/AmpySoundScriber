package org.ampy.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ampy.app.AmpyApplication
import org.ampy.app.data.ProjectRepository
import org.ampy.app.data.ThemeMode
import org.ampy.app.pipeline.ExportFormat
import org.ampy.app.platform.AudioPlayerEngine
import org.ampy.app.platform.DeviceInfo
import org.ampy.audioengine.SpectrogramRenderer
import org.ampy.audioengine.WaveformPeaks
import org.ampy.composerai.ComposeRequest
import org.ampy.composerai.ReferenceStyle
import org.ampy.core.AmpyLog
import org.ampy.core.FloatTensor
import org.ampy.core.History
import org.ampy.core.InferenceDevice
import org.ampy.core.InstrumentKind
import org.ampy.core.JobContext
import org.ampy.core.LogCategory
import org.ampy.core.LogRecord
import org.ampy.core.NoteEdits.addNote
import org.ampy.core.NoteEdits.deleteNote
import org.ampy.core.NoteEdits.moveNote
import org.ampy.core.NoteEdits.resizeNote
import org.ampy.core.NoteEdits.setVelocity
import org.ampy.core.NoteEdits.updateTrack
import org.ampy.core.NoteEvent
import org.ampy.core.Project
import org.ampy.core.Track
import org.ampy.core.Tuning
import org.ampy.core.sub
import org.ampy.learninglab.ParameterTuner
import org.ampy.learninglab.TranscriptionMetrics
import org.ampy.modelmanager.ModelManifest
import org.ampy.modelmanager.ModelTask
import org.ampy.musicbrain.ChordRecognizer
import org.ampy.rhythmlab.Quantizer
import org.ampy.rhythmlab.TempoMap
import org.ampy.transcriber.Activations
import org.ampy.transcriber.BasicPitchConstants
import org.ampy.transcriber.BasicPitchParams
import org.ampy.transcriber.FileActivationStore
import org.ampy.transcriber.TranscriptionProfile
import org.ampy.transcriber.TranscriptionQuality
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val c = (app as AmpyApplication).container
    private val repo: ProjectRepository get() = c.repo

    val theme = MutableStateFlow(c.settings.theme)
    val projects = MutableStateFlow<List<ProjectRepository.Summary>>(emptyList())
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()
    val historyLabels = MutableStateFlow<Pair<String?, String?>>(null to null)
    val recoverable = MutableStateFlow<Project?>(null)
    val selectedTrackId = MutableStateFlow<String?>(null)
    val selectedNoteId = MutableStateFlow<Long?>(null)
    val isPlaying = MutableStateFlow(false)
    val positionSec = MutableStateFlow(0.0)
    val isRecording = MutableStateFlow(false)
    val recordLevel = MutableStateFlow(0f)
    val peaks = MutableStateFlow<Map<String, Pair<FloatArray, FloatArray>>>(emptyMap())
    val spectrogram = MutableStateFlow<Pair<String, Array<FloatArray>>?>(null)
    val logs = MutableStateFlow<List<LogRecord>>(emptyList())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val benchmark = MutableStateFlow<String?>(null)
    val tuningResult = MutableStateFlow<String?>(null)
    val jobs get() = c.jobs.jobs

    private var history: History<Project>? = null
    /** Note prodotte dal riconoscitore prima delle correzioni (per l'apprendimento dai feedback). */
    private val machineNotes = java.util.concurrent.ConcurrentHashMap<String, List<NoteEvent>>()
    private var autosaveJob: Job? = null
    private var ticker: Job? = null
    private var recordFile: File? = null
    private var playerProjectVersion = -1

    init {
        refreshProjects()
        c.diagnostics.addListener { r -> logs.value = (logs.value + r).takeLast(800) }
        logs.value = c.diagnostics.records().takeLast(800)
    }

    fun toast(m: String) { messages.tryEmit(m) }

    // ------------------------------------------------------------------ progetti
    fun refreshProjects() { viewModelScope.launch(Dispatchers.IO) { projects.value = repo.list() } }

    fun openProject(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val l = repo.load(id)
                setProject(l.project, resetHistory = true)
                recoverable.value = l.recovered
            }.onFailure { toast("Impossibile aprire il progetto: ${it.message}"); AmpyLog.e(LogCategory.PROJECT, "vm", "open", it) }
        }
    }

    fun acceptRecovery(accept: Boolean) {
        val r = recoverable.value ?: return
        recoverable.value = null
        if (accept) { setProject(r, resetHistory = true); toast("Salvataggio automatico ripristinato") }
    }

    fun closeProject() { save(); stopPlayback(); c.player.release(); playerProjectVersion = -1; _project.value = null; history = null; machineNotes.clear(); peaks.value = emptyMap(); spectrogram.value = null; refreshProjects() }

    fun deleteProject(id: String) { viewModelScope.launch(Dispatchers.IO) { repo.delete(id); refreshProjects() } }

    private fun setProject(p: Project, resetHistory: Boolean = false, label: String? = null) {
        if (resetHistory) playerProjectVersion = -1
        if (resetHistory || history == null) history = History(p) else if (label != null) history!!.apply(label) { p } else history!!.reset(p)
        _project.value = history!!.current
        historyLabels.value = history!!.undoLabel to history!!.redoLabel
        if (selectedTrackId.value == null || p.tracks.none { it.id == selectedTrackId.value }) selectedTrackId.value = p.tracks.firstOrNull { it.notes.isNotEmpty() }?.id ?: p.tracks.firstOrNull()?.id
        scheduleAutosave()
        computePeaks(p)
    }

    /** Modifica registrata nella cronologia (annulla/ripristina) e autosalvata. */
    fun edit(label: String, f: (Project) -> Project) {
        val h = history ?: return
        h.apply(label, f)
        _project.value = h.current
        historyLabels.value = h.undoLabel to h.redoLabel
        scheduleAutosave()
    }

    fun undo() { history?.let { it.undo(); _project.value = it.current; historyLabels.value = it.undoLabel to it.redoLabel; scheduleAutosave() } }
    fun redo() { history?.let { it.redo(); _project.value = it.current; historyLabels.value = it.undoLabel to it.redoLabel; scheduleAutosave() } }

    fun save() {
        val p = _project.value ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.save(p) }.onSuccess { toast("Progetto salvato") }.onFailure { toast("Salvataggio fallito: ${it.message}") } }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(c.settings.autosaveSec * 1000L)
            _project.value?.let { repo.autosave(it) }
        }
    }

    fun onBackground() {
        if (isPlaying.value) pausePlayback()
        _project.value?.let { p -> viewModelScope.launch(Dispatchers.IO) { repo.autosave(p) } }
    }

    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"

    // ------------------------------------------------------------------ importazione e registrazione
    fun importExternal(uri: Uri, mime: String?) {
        val name = displayName(uri).lowercase()
        when {
            name.endsWith(".mid") || name.endsWith(".midi") || mime == "audio/midi" -> importMidi(uri)
            name.endsWith(".musicxml") || name.endsWith(".xml") || name.endsWith(".mxl") || mime?.contains("musicxml") == true -> importMusicXml(uri)
            else -> importAudio(uri)
        }
    }

    fun importAudio(uri: Uri) {
        val name = displayName(uri)
        c.jobs.launch("Importazione di $name", longRunning = true) { ctx ->
            val id = repo.newId(); repo.dir(id).mkdirs()
            val dec = c.decoder.decode(uri, repo.sourceFile(id), ctx)
            repo.writeSourceMeta(id, dec.sampleRate, dec.channels, name, dec.mime)
            val p = c.pipeline.newProjectFromSource(id, name.substringBeforeLast('.'), dec.pcm)
            dec.pcm.close()
            withContext(Dispatchers.Main) { setProject(p, resetHistory = true); refreshProjects() }
        }
    }

    fun importMidi(uri: Uri) = c.jobs.launch("Importazione MIDI") {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val p = c.pipeline.importMidi(bytes, displayName(uri).substringBeforeLast('.'))
        withContext(Dispatchers.Main) { setProject(p, resetHistory = true); refreshProjects() }
    }

    fun importMusicXml(uri: Uri) = c.jobs.launch("Importazione MusicXML") {
        val n = displayName(uri)
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val p = c.pipeline.importMusicXml(bytes, n.substringBeforeLast('.'), compressed = n.lowercase().endsWith(".mxl"))
        withContext(Dispatchers.Main) { setProject(p, resetHistory = true); refreshProjects() }
    }

    fun importProjectFile(uri: Uri) = c.jobs.launch("Importazione progetto") {
        val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val p = c.pipeline.importProject(text)
        withContext(Dispatchers.Main) { setProject(p, resetHistory = true); refreshProjects() }
    }

    fun startRecording() {
        runCatching {
            val f = File(getApplication<Application>().cacheDir, "rec_${System.currentTimeMillis()}.f32")
            recordFile = f
            c.recorder.start(f)
            isRecording.value = true
            viewModelScope.launch { while (isActive && c.recorder.isRecording) { recordLevel.value = c.recorder.level; delay(50) }; isRecording.value = false }
        }.onFailure { toast("Registrazione non avviata: ${it.message}") }
    }

    fun stopRecording() {
        c.recorder.stop(); isRecording.value = false
        val f = recordFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val id = repo.newId(); repo.dir(id).mkdirs()
            f.renameTo(repo.sourceFile(id)) || run { f.copyTo(repo.sourceFile(id), true); f.delete() }
            repo.writeSourceMeta(id, c.recorder.sampleRate, 1, "Registrazione", "audio/raw")
            val src = repo.openSource(id)!!
            val p = c.pipeline.newProjectFromSource(id, "Registrazione " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALY).format(java.util.Date()), src)
            src.close()
            withContext(Dispatchers.Main) { setProject(p, resetHistory = true); refreshProjects() }
        }
    }

    // ------------------------------------------------------------------ elaborazioni
    private fun runOnProject(title: String, long: Boolean, block: (Project, JobContext) -> Project) {
        val p0 = _project.value ?: return
        stopPlayback()
        c.jobs.launch(title, longRunning = long) { ctx ->
            val p = block(p0, ctx)
            withContext(Dispatchers.Main) { setProject(p, label = title) }
            repo.save(p)
        }
    }

    fun separate(methodId: String) = runOnProject("Separazione", true) { p, ctx -> c.pipeline.separate(p, methodId, ctx) }

    fun transcribe(quality: TranscriptionQuality, trackIds: Set<String>?) = runOnProject("Trascrizione", true) { p, ctx ->
        val r = c.pipeline.transcribe(p, org.ampy.app.pipeline.AmpyPipeline.TranscribeOptions(quality, trackIds), ctx)
        r.tracks.forEach { t -> if (trackIds == null || t.id in trackIds) machineNotes[t.id] = t.notes }
        r
    }

    fun analyze() = runOnProject("Analisi armonica e strutturale", false) { p, ctx -> c.pipeline.analyze(p, ctx) }

    /** Intera catena: separazione → trascrizione → analisi. */
    fun fullPipeline(methodId: String, quality: TranscriptionQuality) = runOnProject("Pipeline completa", true) { p, ctx ->
        var r = c.pipeline.separate(p, methodId, JobContext(ctx.progress.sub(0.0, 0.4), ctx.cancel))
        r = c.pipeline.transcribe(r, org.ampy.app.pipeline.AmpyPipeline.TranscribeOptions(quality), JobContext(ctx.progress.sub(0.4, 0.9), ctx.cancel))
        r.tracks.forEach { machineNotes[it.id] = it.notes }
        c.pipeline.analyze(r, JobContext(ctx.progress.sub(0.9, 1.0), ctx.cancel))
    }

    fun compose(req: ComposeRequest) {
        c.jobs.launch("Composizione ${req.genre.label}", longRunning = false) { ctx ->
            val p = c.pipeline.compose(req, ctx)
            withContext(Dispatchers.Main) { setProject(p, resetHistory = true); refreshProjects() }
        }
    }

    fun referenceStyleFromCurrent(): ReferenceStyle? {
        val p = _project.value ?: return null
        val notes = p.tracks.filter { !it.instrument.isDrum }.flatMap { it.notes }
        if (notes.size < 20) { toast("Trascrivere prima il brano di riferimento"); return null }
        return ReferenceStyle.analyze(notes, p.initialBpm, c.pipeline.chordSegments(p))
    }

    // ------------------------------------------------------------------ editing
    fun selectTrack(id: String) { selectedTrackId.value = id; selectedNoteId.value = null; computeSpectrogram() }
    fun moveNote(trackId: String, noteId: Long, dt: Double, dp: Int) = edit("Sposta nota") { it.moveNote(trackId, noteId, dt, dp) }
    fun resizeNote(trackId: String, noteId: Long, end: Double) = edit("Durata nota") { it.resizeNote(trackId, noteId, end) }
    fun addNote(trackId: String, pitch: Int, start: Double, dur: Double) = edit("Aggiungi nota") { it.addNote(trackId, NoteEvent(pitch, start, start + dur, 90)) }
    fun deleteSelectedNote() { val t = selectedTrackId.value ?: return; val n = selectedNoteId.value ?: return; edit("Elimina nota") { it.deleteNote(t, n) }; selectedNoteId.value = null }
    fun velocity(delta: Int) { val t = selectedTrackId.value ?: return; val n = selectedNoteId.value ?: return; val cur = _project.value?.tracks?.firstOrNull { it.id == t }?.notes?.firstOrNull { it.id == n } ?: return; edit("Dinamica") { it.setVelocity(t, n, cur.velocity + delta) } }
    fun setTrack(trackId: String, label: String, f: (Track) -> Track) { edit(label) { it.updateTrack(trackId, f) }; syncMixer() }
    fun setTuning(trackId: String, tuning: Tuning) = setTrack(trackId, "Accordatura") { it.copy(tuning = tuning) }
    fun setInstrument(trackId: String, k: InstrumentKind) = setTrack(trackId, "Strumento") { it.copy(instrument = k) }
    fun setTempo(bpm: Double) = edit("Tempo") { it.copy(tempo = listOf(org.ampy.core.TempoChange(0.0, bpm)) + it.tempo.drop(1)) }
    fun setKey(tonic: Int, minor: Boolean) = edit("Tonalità") { it.copy(key = org.ampy.core.KeySignature(tonic, minor)) }
    fun setTimeSignature(num: Int, den: Int) = edit("Metro") { it.copy(timeSignature = org.ampy.core.TimeSignature(num, den)) }

    /** Quantizza la traccia selezionata sulla griglia (1/[subdiv] di quarto). */
    fun quantizeTrack(subdiv: Int) {
        val p = _project.value ?: return; val tid = selectedTrackId.value ?: return
        val tm = TempoMap(p.tempo)
        edit("Quantizza") { pr -> pr.updateTrack(tid) { t ->
            t.copy(notes = Quantizer.quantize(t.notes, tm, subdiv, subdiv * 120).map { q ->
                q.note.copy(startSec = tm.beatToSec(q.startTick / (subdiv * 120.0)), endSec = tm.beatToSec((q.startTick + q.durTicks) / (subdiv * 120.0)), userEdited = true)
            })
        } }
    }

    // ------------------------------------------------------------------ riproduzione
    private fun buildChannels(p: Project): List<AudioPlayerEngine.Channel> {
        val list = ArrayList<AudioPlayerEngine.Channel>()
        var rate: Int? = null
        for (t in p.tracks) {
            val src = when {
                t.audioFile != null -> repo.openPcm(p.id, t.audioFile!!)
                t.notes.isNotEmpty() -> c.pipeline.renderTrack(p, t, sampleRate = rate ?: (if (p.sourceAudio != null) p.sampleRate else 44100))
                else -> null
            } ?: continue
            if (rate == null) rate = src.sampleRate
            if (src.sampleRate != rate) { AmpyLog.w(LogCategory.SYNC, "player", "${t.name}: frequenza ${src.sampleRate} diversa da $rate, esclusa dalla riproduzione"); src.close(); continue }
            list.add(AudioPlayerEngine.Channel(t.id, src).apply { volume = t.volume; pan = t.pan; muted = t.muted; solo = t.solo })
        }
        return list
    }

    fun togglePlay() { if (isPlaying.value) pausePlayback() else play() }

    fun play() {
        val p = _project.value ?: return
        c.jobs.launch("Preparazione riproduzione") {
            val version = p.hashCode()
            if (version != playerProjectVersion) { c.player.setChannels(buildChannels(p)); playerProjectVersion = version }
            c.player.play((positionSec.value * c.player.sampleRate).toLong())
            withContext(Dispatchers.Main) { isPlaying.value = true; startTicker() }
        }
    }

    fun pausePlayback() { c.player.pause(); isPlaying.value = false; positionSec.value = c.player.positionSec() }
    fun stopPlayback() { c.player.stop(); isPlaying.value = false; ticker?.cancel(); positionSec.value = 0.0; c.player.seek(0.0) }
    fun seek(sec: Double) { positionSec.value = sec.coerceAtLeast(0.0); c.player.seek(positionSec.value) }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                positionSec.value = c.player.positionSec()
                if (!c.player.isPlaying) { isPlaying.value = false; break }
                delay(33)
            }
        }
    }

    /** Aggiorna mute/solo/volume/pan in tempo reale senza ricostruire le sorgenti. */
    private fun syncMixer() {
        val p = _project.value ?: return
        for (t in p.tracks) c.player.channel(t.id)?.apply { volume = t.volume; pan = t.pan; muted = t.muted; solo = t.solo }
        // se mancano sorgenti (nuove tracce) il lettore verrà ricostruito al prossimo Play
        val needsRebuild = p.tracks.any { tr -> c.player.channel(tr.id) == null && (tr.audioFile != null || tr.notes.isNotEmpty()) }
        playerProjectVersion = if (needsRebuild) -1 else p.hashCode()
    }

    // ------------------------------------------------------------------ visualizzazioni
    private fun computePeaks(p: Project) {
        viewModelScope.launch(Dispatchers.IO) {
            val m = HashMap(peaks.value)
            for (t in p.tracks) if (t.audioFile != null && !m.containsKey(t.id)) {
                runCatching { repo.openPcm(p.id, t.audioFile!!)?.use { m[t.id] = WaveformPeaks.compute(it, 1200) } }
            }
            peaks.value = m
        }
    }

    fun computeSpectrogram() {
        val p = _project.value ?: return
        val t = p.tracks.firstOrNull { it.id == selectedTrackId.value } ?: return
        val rel = t.audioFile ?: run { spectrogram.value = null; return }
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                repo.openPcm(p.id, rel)?.use { src ->
                    val maxFrames = minOf(src.frameCount, src.sampleRate * 180L).toInt() // primi 3 minuti
                    val x = src.readMono(0, maxFrames)
                    spectrogram.value = t.id to SpectrogramRenderer.render(x, src.sampleRate, hop = maxOf(512, maxFrames / 3000 / 256 * 256))
                }
            }.onFailure { AmpyLog.w(LogCategory.UI, "spectrogram", "Spettrogramma non disponibile: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ esportazione
    fun export(format: ExportFormat, trackId: String?, uri: Uri) {
        val p = _project.value ?: return
        c.jobs.launch("Esportazione ${format.label}") { ctx ->
            getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { out ->
                c.pipeline.export(p, trackId?.let { id -> p.tracks.first { it.id == id } }, format, out, ctx)
            }
            toast("Esportato: ${format.label}")
        }
    }

    fun musicXmlFor(trackId: String?): String? {
        val p = _project.value ?: return null
        val tracks = if (trackId == null) p.tracks.filter { it.notes.isNotEmpty() } else p.tracks.filter { it.id == trackId && it.notes.isNotEmpty() }
        if (tracks.isEmpty()) return null
        return runCatching { c.pipeline.musicXml(p, tracks) }.onFailure { AmpyLog.e(LogCategory.MIDI, "score", "Generazione MusicXML fallita", it) }.getOrNull()
    }

    // ------------------------------------------------------------------ apprendimento
    /** Registra le correzioni dell'utente sulla traccia rispetto alla trascrizione automatica. */
    fun saveCorrections(trackId: String) {
        val p = _project.value ?: return
        val t = p.tracks.firstOrNull { it.id == trackId } ?: return
        val orig = machineNotes[trackId] ?: run { toast("Nessuna trascrizione automatica di riferimento in questa sessione"); return }
        viewModelScope.launch(Dispatchers.IO) {
            c.learning.saveCorrection(p.id, trackId, TranscriptionProfile.forInstrument(t.instrument).name, t.audioFile, orig, t.notes)
            val f1 = TranscriptionMetrics.evaluate(t.notes, orig).noteOnset.f1
            toast("Correzione registrata (F1 automatica rispetto alla correzione: ${"%.2f".format(f1)})")
        }
    }

    /** Adatta le soglie di decodifica sulla traccia corretta (richiede le attivazioni in cache). */
    fun tuneOnTrack(trackId: String) {
        val p = _project.value ?: return
        val t = p.tracks.firstOrNull { it.id == trackId } ?: return
        c.jobs.launch("Adattamento parametri") { ctx ->
            val dir = File(repo.cacheDir(p.id), "bp_${t.id}")
            val store = FileActivationStore(dir, resume = true)
            val frames = store.frames.toInt()
            if (frames <= 0) throw IllegalStateException("Nessuna attivazione in cache: trascrivere la traccia con Basic Pitch")
            val (note, onset, _) = store.read(0, frames)
            val profile = TranscriptionProfile.forInstrument(t.instrument)
            val res = ParameterTuner(c.learning).tune(profile.name, listOf(ParameterTuner.Case(note, onset, 0, t.notes)), profile.params(c.settings.quality), ctx)
            tuningResult.value = "Profilo ${res.profile}: F1 %.3f → %.3f (soglia onset %.2f, frame %.2f, durata min %.0f ms). Attenzione: misura sulla stessa traccia usata per l'adattamento.".format(res.before, res.after, res.params.onsetThreshold, res.params.frameThreshold, res.params.minimumNoteLengthMs)
        }
    }

    // ------------------------------------------------------------------ modelli e benchmark
    fun installModel(manifestUri: Uri, modelUri: Uri) = c.jobs.launch("Installazione modello") {
        val cr = getApplication<Application>().contentResolver
        val manifest = ModelManifest.parse(cr.openInputStream(manifestUri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        cr.openInputStream(modelUri)!!.use { c.models.install(manifest, it) }
        // modelli a maschera spettrale: file aggiuntivi per stem nella stessa cartella non sono selezionabili qui
        toast("Modello ${manifest.name} installato e verificato")
    }

    fun removeModel(id: String) { c.models.remove(id); toast("Modello rimosso") }
    fun setActiveModel(task: ModelTask, id: String) = c.models.setActive(task, id)

    /** Misura reale della velocità di Basic Pitch sul dispositivo (10 finestre = ~20 s di audio). */
    fun runBenchmark(device: InferenceDevice) = c.jobs.launch("Benchmark Basic Pitch ($device)") { ctx ->
        val m = c.models.active(ModelTask.TRANSCRIPTION_BASIC_PITCH) ?: throw IllegalStateException("Modello Basic Pitch non installato")
        val (used0, _) = c.diagnostics.sampleMemory("bench-start")
        org.ampy.app.platform.OrtTensorRunner(m.modelFile.path, device).use { r ->
            val x = FloatArray(BasicPitchConstants.AUDIO_N_SAMPLES) { (Math.random() * 0.1 - 0.05).toFloat() }
            r.run(mapOf(BasicPitchConstants.INPUT_NAME to FloatTensor(intArrayOf(1, x.size, 1), x))) // riscaldamento
            val times = (0 until 10).map { i ->
                ctx.cancel.throwIfCancelled()
                val t0 = System.nanoTime()
                r.run(mapOf(BasicPitchConstants.INPUT_NAME to FloatTensor(intArrayOf(1, x.size, 1), x)))
                ctx.progress.report((i + 1) / 10.0, "Finestra ${i + 1}/10")
                (System.nanoTime() - t0) / 1e6
            }
            val (used1, _) = c.diagnostics.sampleMemory("bench-end")
            val avg = times.average()
            benchmark.value = "Dispositivo richiesto $device, attivo ${r.activeDevice}: %.1f ms per finestra di 2 s (min %.1f, max %.1f) → %.0fx tempo reale; RAM +%d MB".format(avg, times.min(), times.max(), 2000 / avg, (used1 - used0) / 1048576)
            AmpyLog.i(LogCategory.TIMING, "bench", benchmark.value!!)
        }
    }

    // ------------------------------------------------------------------ diagnostica e impostazioni
    fun setTheme(t: ThemeMode) { c.settings.theme = t; theme.value = t }

    fun exportDiagnostics(uri: Uri) = c.jobs.launch("Esportazione diagnostica") {
        val tmp = File(getApplication<Application>().cacheDir, "report.txt")
        c.diagnostics.exportReport(tmp, DeviceInfo.summary(getApplication()) + mapOf("latencyMs" to "%.1f".format(c.player.estimatedLatencyMs())))
        getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
        toast("Report diagnostico esportato")
    }

    override fun onCleared() { c.player.release(); super.onCleared() }
}
