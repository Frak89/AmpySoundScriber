#!/usr/bin/env python3
"""
Esporta i modelli Open-Unmix (MIT) in ONNX per AmpySeparator (SpectralMaskModelSeparator).

STATO: script NON eseguito durante lo sviluppo di questa revisione (i pesi si scaricano da Zenodo, non
raggiungibile dall'ambiente di build). Va eseguito e verificato su desktop; poi il modello va provato sul
telefono con il benchmark dell'app prima di dichiararlo funzionante (campo "mobileVerified" del manifest).

    pip install openunmix torch onnx onnxruntime
    python export_openunmix_onnx.py --model umxhq --out umxhq_onnx

Uscita: <out>/<stem>.onnx (vocals/drums/bass/other) + manifest.json pronto per «Installa modello».
Contratto I/O atteso dall'app: input "mix" magnitudine STFT (1, canali, 2049, frame), output "estimate" stessa forma,
STFT n_fft=4096, hop=1024, 44.1 kHz.
"""
import argparse, json, hashlib, pathlib
import torch
from openunmix import utils

ap = argparse.ArgumentParser()
ap.add_argument("--model", default="umxhq")
ap.add_argument("--out", default="umx_onnx")
a = ap.parse_args()
out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)

sep = utils.load_separator(a.model, device="cpu", pretrained=True)
sep.eval()
stem_map = {"vocals": "VOCALS", "drums": "DRUMS", "bass": "BASS", "other": "OTHER"}
for name, model in sep.target_models.items():
    model.eval()
    dummy = torch.rand(1, 2, 2049, 256)
    f = out / f"{stem_map[name].lower()}.onnx"
    torch.onnx.export(model, dummy, str(f), input_names=["mix"], output_names=["estimate"],
                      dynamic_axes={"mix": {3: "frames"}, "estimate": {3: "frames"}}, opset_version=17)
    print("esportato", f)

first = out / "vocals.onnx"
manifest = {
    "id": f"openunmix-{a.model}", "name": f"Open-Unmix {a.model}", "task": "SEPARATION_SPECTRAL_MASK", "format": "ONNX",
    "file": first.name, "sha256": hashlib.sha256(first.read_bytes()).hexdigest(), "version": "1.3", "license": "MIT",
    "source": "https://github.com/sigsep/open-unmix-pytorch", "sampleRate": 44100,
    "stems": ["VOCALS", "DRUMS", "BASS", "OTHER"], "inputName": "mix", "outputNames": ["estimate"],
    "params": {"nFft": 4096, "hop": 1024}, "devices": ["CPU", "XNNPACK"], "mobileVerified": False,
    "notes": "Copiare anche drums.onnx, bass.onnx e other.onnx nella cartella del modello (files/models/<id>/).",
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=1))
print("manifest scritto")
