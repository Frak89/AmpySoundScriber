# Changelog

Formato: [Keep a Changelog](https://keepachangelog.com/it/1.1.0/). Ogni modulo ha la propria versione (vedi `build.gradle.kts`).

## [0.1.0] — 2026-10-28 · Revisione 1 (creazione del progetto)

### Aggiunto
- **R1.1 Infrastruttura**: progetto Gradle multi-modulo, AGP 9.2.1, Gradle 9.4.1, Kotlin 2.3.10, `compileSdk`/`targetSdk` 37 (Android 17), `minSdk` 29; script `tools/verify_core.sh` e `tools/verify_app_pipeline.sh` per verificare senza Android SDK.
- **R1.2 ampy-core 0.1.0**: modello dati del progetto con schema versionato e migrazioni, JSON senza dipendenze, cronologia annulla/ripristina, operazioni di editing, interfacce di inferenza, progresso e annullamento.
- **R1.3 ampy-audio-engine 0.1.0**: FFT/STFT con ricostruzione perfetta, ricampionatore sinc-Kaiser (anche in streaming), WAV 8/16/24/32/float, PCM su disco ad accesso casuale, conversioni in streaming mono/multicanale, segmentazione con sovrapposizione, picchi di forma d'onda, spettrogramma, mixer.
- **R1.4 ampy-transcriber 0.1.0**: port Kotlin di Basic Pitch (finestre, unwrap, note polifoniche, melodia trick, onset inferiti, pitch bend) verificato contro l'originale; profili per strumento; archivio attivazioni riprendibile; decodifica a blocchi per brani lunghi; YIN monofonico.
- **R1.5 ampy-rhythm-lab 0.1.0**: onset spectral flux, tempo con prior log-gaussiano e curva di tempo, beat tracking a programmazione dinamica, mappa di tempo, quantizzazione con scelta automatica della suddivisione, trascrizione percussiva per bande.
- **R1.6 ampy-music-brain 0.1.0**: teoria (scale, accordi diatonici), cromagramma da note/audio, tonalità Krumhansl–Kessler con cambi, accordi per template con rivolti, struttura per auto-similarità.
- **R1.7 ampy-midi-lab 0.1.0**: scrittura/lettura SMF formato 1 (tempo, metro, armatura, programmi, batteria, pitch bend), sintetizzatore di anteprima a blocchi.
- **R1.8 ampy-score-engine 0.1.0**: MusicXML 4.0 (parti, doppio rigo, chiavi 8vb, pause, legature, terzine, sigle, metronomo, rigo TAB), lettura MusicXML/MXL, tablature con Viterbi e accordature.
- **R1.9 ampy-separator 0.1.0**: HPSS con maschere di Wiener, separatori ONNX a maschera spettrale e a forma d'onda, cucitura a segmenti, analisi di separabilità.
- **R1.10 ampy-composer-ai 0.1.0**: composizione gerarchica (9 generi, 1–10 min), motivi con sviluppo, walking bass, groove per genere, fill, condotta delle parti, modulazione finale, stile da brano di riferimento.
- **R1.11 ampy-instrument-ai, ampy-model-manager, ampy-learning-lab, ampy-diagnostics 0.1.0**: classificatore euristico, registro modelli con SHA-256, metriche compatibili mir_eval, feedback e adattamento delle soglie, export dataset, logging/crash handler/analizzatore di log.
- **R1.12 App Android 0.1.0**: decodifica MediaCodec, registrazione, riproduzione multitraccia, ONNX Runtime (CPU/XNNPACK/NNAPI), job con servizio `mediaProcessing`, archivio progetti con autosave recuperabile, interfaccia Compose completa con temi chiaro/scuro/Cyberpunk, spartito Verovio offline con cursore sincronizzato, esportazioni.

### Verificato
- 66 test JVM superati; conformità con basic-pitch 0.4.0 e mir_eval 0.8.2; MIDI validati con mido/pretty_midi; MusicXML con music21 e Verovio; collaudo end-to-end della pipeline con il modello ONNX reale.

### Non verificato
- Compilazione dell'APK e funzionamento su dispositivo (Android SDK non disponibile nell'ambiente di sviluppo). Vedi `docs/STATO_PROGETTO.md`.
