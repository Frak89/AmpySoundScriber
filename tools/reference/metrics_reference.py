#!/usr/bin/env python3
"""Calcola con mir_eval le metriche di riferimento (reference.mid vs note Basic Pitch) per il confronto con AmpyLearningLab."""
import json, sys, pathlib
import numpy as np, pretty_midi, mir_eval

d = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "testdata/bp")
ref = pretty_midi.PrettyMIDI(str(d / "reference.mid")).instruments[0].notes
est = json.load(open(d / "notes_default.json"))
ri = np.array([[n.start, n.end] for n in ref]); rp = np.array([pretty_midi.note_number_to_hz(n.pitch) for n in ref])
ei = np.array([[e[0], e[1]] for e in est]); ep = np.array([pretty_midi.note_number_to_hz(e[2]) for e in est])
out = {}
out["note_onset"] = mir_eval.transcription.precision_recall_f1_overlap(ri, rp, ei, ep, offset_ratio=None)[:3]
out["note_onset_offset"] = mir_eval.transcription.precision_recall_f1_overlap(ri, rp, ei, ep)[:3]
out["onset_only"] = mir_eval.transcription.onset_precision_recall_f1(ri, ei)
out["offset_only"] = mir_eval.transcription.offset_precision_recall_f1(ri, ei)
json.dump({k: [float(x) for x in v] for k, v in out.items()}, open(d / "metrics_mir_eval.json", "w"), indent=1)
print(json.dumps({k: [round(float(x), 4) for x in v] for k, v in out.items()}))
