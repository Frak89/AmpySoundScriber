#!/usr/bin/env python3
"""Ponte ONNX Runtime per i collaudi JVM: legge richieste su stdin, esegue il modello, risponde su stdout.
Protocollo (righe JSON): {"inputs": {name: {"shape": [...], "file": "x.f32"}}} -> {"outputs": {name: {"shape": [...], "file": "y.f32"}}}
"""
import json, sys, numpy as np, onnxruntime as ort
sess = ort.InferenceSession(sys.argv[1], providers=["CPUExecutionProvider"])
outs = [o.name for o in sess.get_outputs()]
print(json.dumps({"inputs": [i.name for i in sess.get_inputs()], "outputs": outs}), flush=True)
for line in sys.stdin:
    req = json.loads(line)
    feeds = {k: np.fromfile(v["file"], dtype="<f4").reshape(v["shape"]) for k, v in req["inputs"].items()}
    res = sess.run(outs, feeds)
    resp = {}
    for name, arr in zip(outs, res):
        f = req["outdir"] + "/" + name.replace(":", "_").replace("/", "_") + ".f32"
        arr.astype("<f4").tofile(f)
        resp[name] = {"shape": list(arr.shape), "file": f}
    print(json.dumps({"outputs": resp}), flush=True)
