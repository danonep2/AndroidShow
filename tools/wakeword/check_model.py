"""Confere se o .onnx treinado tem o formato que o app aceita e mede o acerto nas falas de teste.

Uso: python check_model.py <modelo.onnx> [pasta com .wav positivos]
"""
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort

model = sys.argv[1]
s = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
inputs, outputs = s.get_inputs(), s.get_outputs()
shape = inputs[0].shape
print("entrada:", inputs[0].name, shape, "| saída:", outputs[0].shape)
# Mesmas regras de CustomWakeWords.validate no app.
assert len(inputs) == 1, "o modelo deve ter uma única entrada"
assert len(shape) == 3 and shape[2] == 96 and isinstance(shape[1], int) and shape[1] > 0, "entrada deve ser [1, N, 96]"
score = s.run(None, {inputs[0].name: np.zeros((1, shape[1], 96), np.float32)})[0]
assert np.asarray(score).size == 1, "saída deve ser uma probabilidade [1, 1]"
print("formato compatível com o app ✓", f"({Path(model).stat().st_size // 1024} KB)")

if len(sys.argv) > 2:
    from openwakeword.model import Model

    oww = Model(wakeword_models=[model], inference_framework="onnx")
    key = next(iter(oww.models))
    clips = sorted(Path(sys.argv[2]).glob("*.wav"))
    for group, files in (("pt-BR", [c for c in clips if c.name.startswith("ptbr_")]),
                         ("inglês", [c for c in clips if not c.name.startswith("ptbr_")])):
        files = files[:200]
        if not files:
            continue
        hits = 0
        for f in files:
            oww.reset()
            peak = max(p[key] for p in oww.predict_clip(str(f), padding=1))
            hits += peak >= 0.5
        print(f"acerto nas falas de teste ({group}): {hits}/{len(files)} = {hits / len(files):.0%} com limiar 0,5")
