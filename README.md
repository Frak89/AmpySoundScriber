# AmpySoundScriber

Workstation musicale open source e offline per Android: **ascolta → separa → riconosce → analizza → trascrive → corregge → compone → riproduce → esporta → migliora**.

- **Target:** Android 17 (API 37), `minSdk` 29, ottimizzata per Google Pixel 7 Pro (arm64, Tensor G2).
- **Licenza:** Apache-2.0. Gratuita, senza account, senza cloud: tutti i modelli e il motore di notazione sono inclusi o installabili localmente.
- **Stato:** revisione 0.1.0. Vedi [docs/STATO_PROGETTO.md](docs/STATO_PROGETTO.md) per cosa è verificato, cosa no e i limiti.

## Funzioni principali

| Area | Implementazione |
|---|---|
| Importazione | MP3, WAV, FLAC, OGG/Opus, M4A/AAC tramite MediaCodec (decodifica in streaming su disco, nessun limite di durata); MIDI; MusicXML (anche `.mxl`); registrazione dal microfono (float 48 kHz) |
| Separazione | DSP armonico/percussivo (sempre disponibile); modelli neurali ONNX a maschera spettrale (Open-Unmix) o a forma d'onda (Demucs) installabili; avvisi automatici quando due sorgenti non sono separabili |
| Trascrizione | Spotify Basic Pitch (ONNX incluso, port Kotlin verificato contro l'originale), batteria per bande, YIN monofonico di riserva; confidenza per ogni nota; modalità veloce e massima qualità; ripresa da checkpoint |
| Analisi | Tempo e variazioni di BPM, beat tracking, tonalità e cambi, accordi (anche rivolti), struttura del brano, suddivisioni (crome/terzine/semicrome) |
| Notazione | MusicXML 4.0 per parte o partitura completa, doppio rigo per pianoforte, chiavi 8vb, rigo TAB, sigle accordi; rendering offline con Verovio (WASM) e cursore sincronizzato; PDF tramite stampa di sistema |
| Tablature | Chitarra (standard, Drop D, ½ tono sotto, DADGAD) e basso (4/5 corde, Drop D), assegnazione corda/tasto con Viterbi |
| Editor | Piano roll (sposta, allunga, aggiungi, elimina, dinamica, quantizza), annulla/ripristina illimitato, salvataggio automatico recuperabile |
| Composizione | AmpyComposer gerarchico: struttura → armonia → motivi sviluppati → basso → batteria → accompagnamento; 9 generi, 1–10 minuti, strumenti/BPM/tonalità/struttura/complessità/carattere configurabili; analisi di stile di brani di riferimento senza copiarne il materiale |
| Mixer e riproduzione | Volume, pan, mute, solo in tempo reale su stem e tracce sintetizzate |
| Esportazione | Per traccia e complessiva: MIDI, MusicXML, WAV (stem o ricostruzione sintetizzata), tablatura, PDF, progetto `.ampy.json` |
| Apprendimento | Registrazione delle correzioni, statistiche degli errori, adattamento verificabile delle soglie (F1 prima/dopo), export di dataset per fine-tuning su desktop |
| Diagnostica | Log strutturati per categoria, crash handler, memoria, tempi, analisi automatica dei log, report esportabile |
| Interfaccia | Temi chiaro, scuro e Cyberpunk; timeline multitraccia, forma d'onda, spettrogramma, piano roll, spartito, mixer, gestore modelli, console diagnostica, avanzamento reale con annullamento |

## Struttura

```
app/                         applicazione Android (Compose, ONNX Runtime, MediaCodec, AudioTrack/AudioRecord)
  src/main/assets/models/    Basic Pitch ONNX (Apache-2.0) + manifest con SHA-256
  src/main/assets/score/     Verovio 6.3.0 (LGPL-3.0) + pagina di rendering
modules/                     13 moduli Kotlin/JVM indipendenti, versionati e testati
  ampy-core                  modello dati, JSON, cronologia, progresso, interfacce di inferenza
  ampy-audio-engine          FFT/STFT, ricampionamento, WAV, PCM su disco, segmentazione, mixer
  ampy-separator             HPSS, modelli a maschera spettrale e a forma d'onda, analisi di separabilità
  ampy-transcriber           Basic Pitch (finestre, unwrap, note, pitch bend), YIN
  ampy-score-engine          MusicXML (scrittura/lettura), tablature
  ampy-midi-lab              Standard MIDI File (scrittura/lettura), sintetizzatore di anteprima
  ampy-composer-ai           composizione gerarchica, stile di riferimento
  ampy-instrument-ai         riconoscimento euristico della famiglia strumentale
  ampy-rhythm-lab            onset, tempo, beat, quantizzazione, batteria
  ampy-music-brain           teoria, tonalità, accordi, struttura
  ampy-model-manager         registro modelli, verifica SHA-256, catalogo valutato
  ampy-learning-lab          metriche compatibili mir_eval, feedback, adattamento parametri
  ampy-diagnostics           logging, crash handler, analizzatore di log
tools/                       verifica senza Android SDK, riferimenti Python, esportazione modelli
docs/                        architettura, modelli, stato del progetto
```

## Compilazione

Requisiti: Android Studio con **Android SDK Platform 37** e **Build-Tools 37**, JDK 17 o superiore. Il wrapper usa **Gradle 9.4.1** (minimo richiesto da AGP 9.2).

```bash
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew test                        # test JVM dei 13 moduli
./gradlew :app:installDebug           # installa su dispositivo collegato (Pixel 7 Pro)
```

Verifica dei moduli senza Android SDK (usa il compilatore Kotlin incluso in una distribuzione Gradle 8.x):

```bash
KOTLIN_LIB=/percorso/gradle-8.x/lib tools/verify_core.sh          # compila e testa i 13 moduli
python3 tools/reference/basic_pitch_reference.py testdata/bp       # rigenera i riferimenti (pip install basic-pitch onnxruntime librosa pretty_midi)
python3 tools/reference/metrics_reference.py testdata/bp           # riferimento mir_eval
PYTHON=python3 tools/verify_app_pipeline.sh                         # collaudo end-to-end della pipeline con il modello reale
```

## Prima esecuzione

1. All'avvio il modello Basic Pitch incluso viene installato e verificato (SHA-256).
2. **Importa audio** → scheda **Elabora** → *Esegui tutto* (separazione DSP + trascrizione + analisi) oppure i singoli passi.
3. Correggi nel **Piano roll**, ascolta con il **Mixer**, leggi lo **Spartito**, esporta da **Esporta**.
4. Per separare voce/basso/batteria/altro installa un modello neurale (vedi [docs/MODELS.md](docs/MODELS.md)).
