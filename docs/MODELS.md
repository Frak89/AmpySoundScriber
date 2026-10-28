# Modelli IA

## Inclusi

| Modello | Compito | Licenza | File | Verifica |
|---|---|---|---|---|
| Basic Pitch ICASSP 2022 (Spotify) | Trascrizione polifonica 88 tasti + onset + contorno (pitch bend) | Apache-2.0 | `assets/models/basic-pitch/nmp.onnx` (225 KB) | SHA-256 `2c3c1d14…a0ec`; uscite identiche all'implementazione Python (vedi STATO_PROGETTO) |

Ingresso `serving_default_input_2:0` (batch, 43844, 1) a 22050 Hz mono; uscite `StatefulPartitionedCall:1` (note),
`:2` (onset), `:0` (contorno, 264 bin) con 172 frame per finestra.

## Installabili dall'app (scheda Modelli → «Installa modello»)

Si seleziona prima il `manifest.json`, poi il file `.onnx` (nell'esempio i commenti `//` sono solo esplicativi: il file reale è JSON puro). L'impronta SHA-256 del manifest viene verificata in
installazione e su richiesta; un file alterato viene segnalato.

```jsonc
{
  "id": "openunmix-umxhq",
  "name": "Open-Unmix UMX-HQ",
  "task": "SEPARATION_SPECTRAL_MASK",          // oppure SEPARATION_WAVEFORM, TRANSCRIPTION_BASIC_PITCH
  "format": "ONNX",
  "file": "vocals.onnx",
  "sha256": "…",
  "version": "1.3",
  "license": "MIT",
  "source": "https://github.com/sigsep/open-unmix-pytorch",
  "sampleRate": 44100,
  "stems": ["VOCALS", "DRUMS", "BASS", "OTHER"],
  "inputName": "mix",
  "outputNames": ["estimate"],
  "params": { "nFft": 4096, "hop": 1024 },     // waveform: { "segment": 343980, "overlap": 44100 }
  "devices": ["CPU", "XNNPACK"],
  "mobileVerified": false,                      // true solo dopo misura reale su telefono
  "notes": "…"
}
```

Valori di `stems`: `VOCALS, DRUMS, BASS, OTHER, GUITAR, PIANO, WINDS`. Con più di 4 sorgenti il modello compare nella
modalità «Multistrumento».

### Contratti I/O

- **SEPARATION_SPECTRAL_MASK** (stile Open-Unmix): un file ONNX per sorgente (`<stem>.onnx` nella cartella del modello);
  ingresso magnitudine STFT `(1, canali, nFft/2+1, frame)`, uscita stima della magnitudine con la stessa forma.
  L'app combina le stime con una maschera di Wiener morbida e aggiunge uno stem «Residuo».
- **SEPARATION_WAVEFORM** (stile Demucs solo forma d'onda): ingresso `(1, 2, segment)`, uscita `(1, S, 2, segment)`
  nell'ordine di `stems`; elaborazione a segmenti con dissolvenza incrociata.

Script di conversione (da eseguire su desktop, **non eseguiti in questa revisione**): `tools/export/export_openunmix_onnx.py`,
`tools/export/export_waveform_model_onnx.py`.

## Valutazione dei progetti indicati nel capitolato

Il catalogo è consultabile anche nell'app (`ModelCatalog`).

| Progetto | Licenza | Esito |
|---|---|---|
| Spotify Basic Pitch | Apache-2.0 | **Integrato**: piccolo, veloce, polifonico, già in ONNX |
| Demucs / HTDemucs | MIT | Supportato via ONNX esterno; HTDemucs richiede STFT esterna (rev. 0.5) |
| Open-Unmix | MIT (UMXL: CC BY-NC-SA, escluso) | Supportato via ONNX esterno; candidato principale per rev. 0.2 |
| Spleeter | MIT | Non integrato: modelli TF1, conversione onerosa, qualità inferiore a Demucs |
| MDX / UVR | codice MIT, pesi variabili | Non integrato finché le licenze dei pesi non sono verificate |
| MT3 | Apache-2.0 | Non praticabile su smartphone (T5 in JAX, centinaia di MB) |
| Omnizart | MIT | Rimandato: TF2, conversione da verificare |
| piano_transcription_inference | Apache-2.0 | Candidato per modalità alta qualità su desktop (~170 MB) |
| Onsets and Frames | Apache-2.0 | Rimandato (TF1; esiste TFLite per piano) |
| Sheet Sage | MIT | Solo desktop/server (dipende da Jukebox) |
| AudiTranscribe | GPL-3.0 | Usato come riferimento di UX; codice non incluso (licenza) |
| MusicTranscription, songscribe, Music-Transcription-with-Semantic-Segmentation e altri repository GitHub | varie | Esaminati come riferimento; nessuno offre modelli più adatti al mobile di Basic Pitch |
| Verovio | LGPL-3.0 | **Integrato** come file JS/WASM separato e non modificato (sostituibile) |
| MuseScore | GPL-3.0 | Interoperabilità via MusicXML/MIDI |
| klang.io, Lunaverus AnthemScore | proprietari | Esclusi (non open source) |
