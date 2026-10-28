# Architettura

## Principi

- **Moduli indipendenti** (sezione 1): 13 moduli Gradle Kotlin/JVM senza dipendenze Android, ognuno con versione propria
  (`build.gradle.kts` radice → `ampyModuleVersions`), `ModuleInfo` interrogabile a runtime e test JUnit.
- **Inversione delle dipendenze per la piattaforma**: i moduli IA dipendono solo da `TensorRunner`
  (`ampy-core/Inference.kt`); l'app fornisce `OrtTensorRunner` (ONNX Runtime Android). Su desktop si può fornire
  un'implementazione con ONNX Runtime Java/CUDA/DirectML senza toccare i moduli.
- **Memoria costante per brani lunghi** (sezione 2): l'audio decodificato è un file PCM float32 su disco
  (`RawFloatFile`) letto ad accesso casuale; tutte le fasi lavorano a segmenti sovrapposti (`Segmenter`,
  `SegmentedRenderer`, `PcmConvert`, `FileActivationStore`, decodifica note a blocchi da 5 min, sintesi a blocchi da 30 s).
- **Checkpoint e annullamento**: `JobContext` = `ProgressSink` (avanzamento reale) + `CancellationToken`.
  Le attivazioni di Basic Pitch sono scritte finestra per finestra e la trascrizione riprende da dove era stata interrotta
  (`TranscriptionCheckpoint` verifica che audio e modello siano gli stessi).
- **Niente funzioni simulate**: dove manca un modello, l'app lo dichiara (avvisi su tracce e progetto) e usa il metodo DSP disponibile.

## Grafo delle dipendenze

```
ampy-core ◄─┬─ ampy-diagnostics
            ├─ ampy-model-manager
            └─ ampy-audio-engine ◄─┬─ ampy-separator
                                   ├─ ampy-instrument-ai
                                   ├─ ampy-midi-lab
                                   └─ ampy-rhythm-lab ◄─┬─ ampy-transcriber ◄── ampy-learning-lab (+ midi-lab)
                                                        └─ ampy-music-brain ◄─┬─ ampy-score-engine
                                                                              └─ ampy-composer-ai (+ midi-lab)
app ──► tutti i moduli + Android (Compose, MediaCodec, AudioTrack/AudioRecord, ONNX Runtime, WebView/Verovio)
```

## API principali per modulo

| Modulo | Punti d'ingresso |
|---|---|
| ampy-core | `Project`, `Track`, `NoteEvent`, `ProjectCodec` (schema versionato + migrazioni), `History<T>` (annulla/ripristina), `NoteEdits`, `TensorRunner`, `JobContext`, `AmpyLog`, `Json` |
| ampy-audio-engine | `Fft`, `Stft`, `Resampler` (anche in streaming), `WavCodec`, `PcmSource`/`RawFloatFile`, `PcmConvert.toMono/toRate`, `Segmenter`, `WaveformPeaks`, `SpectrogramRenderer`, `Mixer` |
| ampy-separator | `SourceSeparator`, `HpssSeparator`, `SpectralMaskModelSeparator`, `WaveformModelSeparator`, `SeparabilityAnalyzer` |
| ampy-transcriber | `BasicPitchTranscriber.infer/decode/transcribe`, `BasicPitchNotes`, `BasicPitchParams`, `TranscriptionProfile`, `FileActivationStore`, `MonoPitchTranscriber` |
| ampy-rhythm-lab | `OnsetEnvelope`, `TempoEstimator.estimate/tempoCurve`, `BeatTracker`, `TempoMap`, `Quantizer`, `DrumTranscriber` |
| ampy-music-brain | `Scale`, `Chord`, `Chromagram`, `KeyDetector.detect/keyChanges`, `ChordRecognizer`, `StructureAnalyzer` |
| ampy-midi-lab | `MidiWriter`, `MidiReader`, `PreviewSynth.renderRange/renderMix` |
| ampy-score-engine | `MusicXmlWriter.write(project, tracks, ScoreOptions)`, `MusicXmlReader.read/readMxl`, `TabGenerator.assign/renderAscii` |
| ampy-composer-ai | `HierarchicalComposer(ComposeRequest).planSections/compose/chordSegments`, `ReferenceStyle.analyze/applyTo` |
| ampy-instrument-ai | `InstrumentClassifier.features/classify` |
| ampy-model-manager | `ModelManifest`, `ModelRegistry.install/verify/active/setActive`, `ModelCatalog` |
| ampy-learning-lab | `TranscriptionMetrics.evaluate/match`, `SeparationMetrics`, `LearningStore`, `ParameterTuner` |
| ampy-diagnostics | `DiagnosticsHub` (ring buffer + file ruotati + crash handler + report), `LogAnalyzer.analyze` |

## App Android

| Pacchetto | Contenuto |
|---|---|
| `platform` | `AndroidAudioDecoder` (MediaExtractor/MediaCodec → float32 su disco), `AudioRecorderEngine`, `AudioPlayerEngine` (mix multitraccia, latenza), `OrtTensorRunner` (CPU/XNNPACK/NNAPI con fallback), `DeviceInfo` |
| `jobs` | `JobManager` (coroutine, avanzamento, annullamento), `ProcessingService` (servizio in primo piano `mediaProcessing`) |
| `data` | `ProjectRepository` (salvataggio atomico, autosave recuperabile, stem/render/cache), `Settings` |
| `pipeline` | `AmpyPipeline`: importazione, separazione, trascrizione, analisi, composizione, sintesi, esportazione — senza API Android, collaudata sulla JVM |
| `ui` | `AppViewModel` + schermate Compose (Elabora, Timeline, Piano roll, Spartito, Tablatura, Spettrogramma, Mixer, Esporta, Componi, Modelli, Apprendi, Diagnostica, Impostazioni), temi chiaro/scuro/Cyberpunk |

### Sincronizzazione audio/spartito

Il lettore riporta la posizione come testina di `AudioTrack` + offset; la UI la invia ~30 volte al secondo alla pagina
Verovio (`ampySetTime(ms)`), che usa la timemap calcolata dallo stesso MusicXML (stessa mappa di tempo del progetto).
Toccare una nota nello spartito restituisce il suo tempo (`getTimeForElement`) e sposta la riproduzione.

### Formato progetto

`projects/<id>/project.json` (schema 1). Ogni modifica allo schema richiede un blocco in `ProjectCodec.migrate`
e un test; i progetti con schema più recente vengono rifiutati con un messaggio esplicito invece di essere corrotti.
