#!/usr/bin/env python3
"""
Genera i dati di riferimento per verificare il port Kotlin di Basic Pitch.

Requisiti: pip install basic-pitch onnxruntime librosa pretty_midi soundfile
(non serve TensorFlow: si usa il modello ONNX incluso nel pacchetto).

Uscita in testdata/bp/:
  input.wav               audio di prova (22050 Hz mono)
  reference.mid           MIDI "verità" usato per sintetizzare l'audio
  windows.f32             finestre in ingresso al modello (n_win x 43844)
  raw_{note,onset,contour}.f32   uscite grezze per finestra (n_win x 172 x bins)
  {note,onset,contour}.f32       uscite dopo unwrap
  meta.json               dimensioni
  notes_default.json      note di Basic Pitch con parametri predefiniti (+pitch bend)
  notes_custom.json       note con parametri alternativi (soglie/frequenze/no melodia)
"""
import json, os, sys, pathlib
import numpy as np
import pretty_midi, soundfile as sf

from basic_pitch import ICASSP_2022_MODEL_PATH
from basic_pitch.inference import Model, get_audio_input, unwrap_output
from basic_pitch import note_creation as infer
from basic_pitch.constants import AUDIO_SAMPLE_RATE, FFT_HOP

out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "testdata/bp")
out.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(42)

# --- brano di prova: progressione di accordi + melodia + basso, 14 s ---------------
pm = pretty_midi.PrettyMIDI(initial_tempo=100)
piano = pretty_midi.Instrument(program=0)
chords = [[60, 64, 67], [57, 60, 64], [53, 57, 60], [55, 59, 62, 65]]
t = 0.0
for rep in range(2):
    for c in chords:
        for p in c:
            piano.notes.append(pretty_midi.Note(velocity=int(rng.integers(70, 100)), pitch=p, start=t, end=t + 1.6))
        piano.notes.append(pretty_midi.Note(velocity=95, pitch=c[0] - 24, start=t, end=t + 1.6))
        for k, mp in enumerate([72, 74, 76, 79]):
            s = t + k * 0.4
            piano.notes.append(pretty_midi.Note(velocity=90, pitch=mp + (rep * 2), start=s, end=s + 0.35))
        t += 1.6
pm.instruments.append(piano)
pm.write(str(out / "reference.mid"))

def synth(pm, fs):
    y = np.zeros(int(fs * (pm.get_end_time() + 1.0)))
    for inst in pm.instruments:
        for n in inst.notes:
            f0 = 440 * 2 ** ((n.pitch - 69) / 12)
            ln = int((n.end - n.start + 0.3) * fs)
            tt = np.arange(ln) / fs
            env = np.minimum(1, tt / 0.005) * np.exp(-tt * 2.5) * np.where(tt < (n.end - n.start), 1.0, np.exp(-(tt - (n.end - n.start)) * 30))
            sig = sum((0.6 ** h) * np.sin(2 * np.pi * f0 * (h + 1) * tt) for h in range(5))
            s = int(n.start * fs)
            y[s:s + ln] += (n.velocity / 127) * 0.12 * env * sig
    return y

y = synth(pm, AUDIO_SAMPLE_RATE)
y += rng.normal(0, 0.002, size=y.shape)
y = (y / np.max(np.abs(y)) * 0.8).astype(np.float32)
sf.write(str(out / "input.wav"), y, AUDIO_SAMPLE_RATE, subtype="FLOAT")

# --- inferenza (stessa sequenza di basic_pitch.inference.run_inference) ----------
_mp = pathlib.Path(str(ICASSP_2022_MODEL_PATH))
model = Model(_mp if _mp.suffix == ".onnx" else _mp.parent / "nmp.onnx")
n_overlapping_frames = 30
overlap_len = n_overlapping_frames * FFT_HOP
hop_size = 43844 - overlap_len
outputs = {"note": [], "onset": [], "contour": []}
windows = []
for audio_windowed, _, audio_original_length in get_audio_input(str(out / "input.wav"), overlap_len, hop_size):
    windows.append(audio_windowed.astype(np.float32).reshape(-1))
    for k, v in model.predict(audio_windowed).items():
        outputs[k].append(v)
np.stack(windows).astype("<f4").tofile(out / "windows.f32")
unwrapped = {}
for k in outputs:
    raw = np.concatenate(outputs[k]).astype("<f4")
    raw.tofile(out / f"raw_{k}.f32")
    unwrapped[k] = unwrap_output(raw, audio_original_length, n_overlapping_frames).astype("<f4")
    unwrapped[k].tofile(out / f"{k}.f32")

json.dump({"n_windows": len(windows), "samples": int(audio_original_length), "frames": int(unwrapped["note"].shape[0])}, open(out / "meta.json", "w"))

def dump(name, **kw):
    params = dict(onset_thresh=0.5, frame_thresh=0.3, min_note_len=11, infer_onsets=True, min_freq=None, max_freq=None,
                  include_pitch_bends=True, melodia_trick=True)
    params.update(kw)
    _, notes = infer.model_output_to_notes({k: v.copy() for k, v in unwrapped.items()}, **params)
    json.dump([[float(s), float(e), int(p), float(a), [int(b) for b in pb] if pb is not None else None] for s, e, p, a, pb in notes],
              open(out / name, "w"))
    return notes

d = dump("notes_default.json")
c = dump("notes_custom.json", onset_thresh=0.6, frame_thresh=0.25, min_note_len=8, min_freq=60.0, max_freq=1000.0, melodia_trick=False, include_pitch_bends=False)
print(f"finestre={len(windows)} frame={unwrapped['note'].shape[0]} note_default={len(d)} note_custom={len(c)}")
