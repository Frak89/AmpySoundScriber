#!/usr/bin/env python3
"""
Esporta un modello di separazione a forma d'onda PyTorch in ONNX per WaveformModelSeparator.

Contratto atteso dall'app: input "mix" (1, 2, N) a 44.1 kHz con N fisso (= params.segment),
output (1, S, 2, N) con le S sorgenti nell'ordine di "stems" del manifest.

STATO: NON eseguito in questa revisione. Adatto a modelli Demucs solo-forma-d'onda (es. Demucs v2, MIT).
I modelli ibridi (HTDemucs v4) contengono STFT/iSTFT complessi che non si esportano direttamente: per loro
serve separare STFT e rete (approccio del progetto sevagh/demucs.onnx) — non ancora supportato dall'app.

    pip install demucs torch onnx onnxruntime
    python export_waveform_model_onnx.py --name demucs48_hq --segment 343980
"""
import argparse, json, hashlib, pathlib
import torch

ap = argparse.ArgumentParser()
ap.add_argument("--name", default="demucs48_hq")
ap.add_argument("--segment", type=int, default=343980)
ap.add_argument("--out", default="waveform_onnx")
a = ap.parse_args()
out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)

from demucs.pretrained import get_model  # noqa: E402
bag = get_model(a.name)
model = bag.models[0] if hasattr(bag, "models") else bag
model.eval()
if any("Hybrid" in type(m).__name__ or "HT" in type(m).__name__ for m in [model]):
    raise SystemExit("Modello ibrido: esportazione diretta non supportata (vedi docstring).")
dummy = torch.rand(1, 2, a.segment)
f = out / f"{a.name}.onnx"
torch.onnx.export(model, dummy, str(f), input_names=["mix"], output_names=["stems"], opset_version=17)
stems = [s.upper() for s in model.sources]
manifest = {
    "id": f"demucs-{a.name}", "name": f"Demucs {a.name}", "task": "SEPARATION_WAVEFORM", "format": "ONNX", "file": f.name,
    "sha256": hashlib.sha256(f.read_bytes()).hexdigest(), "version": "export", "license": "MIT",
    "source": "https://github.com/facebookresearch/demucs", "sampleRate": 44100, "stems": stems,
    "inputName": "mix", "outputNames": ["stems"], "params": {"segment": a.segment, "overlap": 44100},
    "devices": ["CPU", "XNNPACK"], "mobileVerified": False,
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=1))
print("esportato", f, "sorgenti", stems)
