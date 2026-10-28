# Stato del progetto — revisione 0.1.0 (28 ottobre 2026)

Documento richiesto dalla sezione 14 del capitolato: valutazione delle funzioni realmente operative, problemi e
limiti, piano di integrazione, test e funzioni mancanti. Nessuna percentuale è stimata: i numeri riportati sono misurati.

## 1. Stato iniziale

Non esisteva un repository precedente: il progetto è stato creato da zero (nessuna funzionalità preesistente da preservare).

## 2. Verifiche eseguite in questa revisione

Ambiente di sviluppo: Linux, JDK 21, compilatore Kotlin 2.0.21; **nessun Android SDK disponibile** e repository Maven/Google bloccati dalla rete.

| Verifica | Esito | Come |
|---|---|---|
| 13 moduli core compilati | ✅ | `tools/verify_core.sh` |
| 66 test automatici JVM | ✅ 66/66 | JUnit 4 |
| Port Basic Pitch identico all'originale | ✅ | finestre, unwrap, note, pitch bend e parametri personalizzati confrontati con `basic-pitch 0.4.0` eseguito con il modello ONNX: 94/94 note identiche (tempi entro 1e-9 s) |
| Metriche identiche a `mir_eval 0.8.2` | ✅ | precision/recall/F1 di note, onset e offset uguali entro 1e-9 |
| MIDI valido | ✅ | riletto da `mido` e `pretty_midi` (tempo, armatura, metro, programmi, batteria) |
| MusicXML valido | ✅ | riletto da `music21` (tutte le battute di durata corretta, anche su partitura di 172 battute × 10 righi) |
| Rendering spartito | ✅ | la pagina `score.html` dell'app con Verovio 6.3.0 in Chromium headless: 2 pagine, 9575 note, cursore funzionante, 0 errori |
| Pipeline dell'app end-to-end | ✅ | `tools/verify_app_pipeline.sh`: importazione → trascrizione con il modello reale → separazione → analisi → salvataggio/recupero → 7 esportazioni → composizione di 5 minuti |
| **Compilazione dell'APK** | ❌ **non eseguita** | manca l'Android SDK nell'ambiente: il codice del modulo `app` che usa API Android/Compose **non è stato compilato né provato su dispositivo** |

### Risultati misurati (brano sintetico di prova, 14 s, piano polifonico a 4–5 voci)

Basic Pitch, parametri predefiniti (stessi numeri in Kotlin e con mir_eval):

| Metrica | Valore |
|---|---|
| Note F1 (onset ± 50 ms + altezza) | 0,788 (P 0,670 · R 0,955) |
| Note F1 con offset | 0,488 |
| Onset F1 | 0,825 |
| Accuratezza altezze (sulle note con onset corretto) | 0,955 |
| Errore medio di onset | 7,7 ms |
| Inferenza (JVM + ONNX Runtime CPU desktop) | 0,25 s per 9 finestre (18 s di audio) |

Adattamento delle soglie sullo stesso brano: F1 0,788 → 0,892. **Misura in-sample** (adattato e valutato sullo stesso file): indica che il meccanismo funziona, non la generalizzazione.

Questi numeri **non** rappresentano la precisione su musica reale: il brano è sintetico. Per misure realistiche servono dataset come MAESTRO, GuitarSet, Slakh (vedi §5).

## 3. Problemi e limiti noti

1. **APK non compilato qui.** Le API usate sono quelle documentate (AGP 9.2, Compose BOM 2026.09, ONNX Runtime 1.27), ma possono servire piccole correzioni alla prima compilazione in Android Studio. Il wrapper punta a Gradle 9.4.1.
2. **Separazione neurale non inclusa.** Nessun modello Demucs/Open-Unmix è incluso: i pesi non erano scaricabili nell'ambiente e i modelli vanno convertiti e misurati su telefono. L'app integra i separatori ONNX (pipeline collaudata con modelli di prova) ma senza un modello installato separa solo armonico/percussivo. Gli script di esportazione in `tools/export` **non sono stati eseguiti**.
3. **HTDemucs (v4) non supportato direttamente**: contiene STFT complesse non esportabili così come sono; richiede di separare STFT e rete.
4. **Riconoscimento strumenti euristico** (registro, polifonia, timbro): confidenza volutamente limitata a 0,8. Il nome dello stem del separatore neurale è più affidabile.
5. **Tempo:** ambiguità di metà/doppio/×1,5 possibile (sul brano di prova a 100 BPM con melodia a crome puntate è stato stimato 150,6 BPM). Il BPM è modificabile dall'utente.
6. **Notazione:** ogni rigo è ridotto a una sequenza di accordi (una voce per rigo); voci indipendenti nello stesso rigo, abbellimenti, articolazioni e legature di portamento non sono ancora generati. Pause, legature di valore, terzine e punti sono gestiti.
7. **Sintesi di anteprima additiva** (nessun SoundFont): serve a verificare la trascrizione, non a un ascolto realistico.
8. **Android 17:** la riproduzione in background è vietata senza servizio dedicato, quindi l'audio si mette in pausa quando l'app va in background. Le elaborazioni lunghe continuano con un servizio `mediaProcessing` (Android 15+), soggetto al limite di durata di sistema.
9. **NNAPI** è deprecato da Android 15; l'app ricade su CPU se un operatore non è supportato. XNNPACK è il backend consigliato.
10. **Fine-tuning dei pesi sul telefono non implementato** (non sostenibile): l'apprendimento adatta soglie verificabili ed esporta dataset per l'addestramento su desktop.
11. **iOS / Windows / Linux:** i moduli core sono Kotlin/JVM puro e riutilizzabili su desktop; la UI esiste solo per Android. iOS richiederebbe Kotlin Multiplatform (non ancora configurato).

## 4. Piano di integrazione (prossime revisioni)

| Rev. | Obiettivo | Criterio di accettazione |
|---|---|---|
| 0.1.1 | Prima compilazione in Android Studio, correzioni di build, test strumentali su Pixel 7 Pro | APK installato, log di build allegato, benchmark Basic Pitch misurato con CPU/XNNPACK/NNAPI |
| 0.2 | Esportare Open-Unmix UMX-HQ in ONNX e misurarlo sul telefono | SDR su MUSDB18-HQ (subset) riportato; tempo/memoria su 3 min di audio |
| 0.3 | Valutazione quantitativa su MAESTRO (piano), GuitarSet (chitarra), Slakh2100 (multi-strumento) | Tabella F1 per strumento e per modalità veloce/qualità |
| 0.4 | Notazione: voci multiple per rigo, articolazioni, dinamiche, legature di portamento | Validazione MusicXML + revisione di musicisti |
| 0.5 | Modelli specializzati (piano ad alta risoluzione, batteria neurale) e HTDemucs con STFT esterna | Confronto F1/SDR con la revisione 0.3 |
| 0.6 | Desktop (Compose Multiplatform) riusando i moduli core; poi iOS | Build desktop Linux/Windows |

## 5. Procedura di valutazione quantitativa

`AmpyLearningLab` contiene metriche compatibili mir_eval (verificate). Per valutare su un dataset: trascrivere ogni brano con la pipeline (o con il collaudo JVM), leggere il MIDI di riferimento con `MidiReader` e usare `TranscriptionMetrics.evaluate(ref, est, runtimeMs, memoryMb)`; per la separazione `SeparationMetrics.sdr/siSdr`.

## 6. Riepilogo

**Implementato e verificato:** decodifica/streaming (core), trascrizione Basic Pitch conforme all'originale, YIN, batteria, analisi (tempo, tonalità, accordi, struttura), MusicXML/MIDI/tablature, rendering spartito, composizione completa, metriche, adattamento parametri, registro modelli, diagnostica, salvataggio/recupero.

**Implementato ma non verificato su dispositivo:** tutta l'interfaccia Android (Compose), decodifica MediaCodec, registrazione, riproduzione multitraccia, ONNX Runtime Android, servizio in primo piano, stampa PDF.

**Mancante:** modelli di separazione neurale inclusi, valutazione su dataset reali, voci multiple in notazione, versioni desktop/iOS, fine-tuning su dispositivo.
