"""Gera falas da palavra com vozes pt-BR do Piper e junta às amostras positivas do treino.

O gerador do openWakeWord só tem vozes em inglês; estas garantem a pronúncia brasileira.
Roda depois do --generate_clips e antes do --augment_clips (o aumento usa todo .wav da pasta).

Uso: python ptbr_samples.py <Palavra> <runs/nome> <quantidade>
"""
import random
import sys
import urllib.request
import wave
from io import BytesIO
from pathlib import Path

import numpy as np
import scipy.io.wavfile
from scipy.signal import resample_poly

from piper import PiperVoice  # piper-tts 1.2

word, run_dir, total = sys.argv[1], Path(sys.argv[2]), int(sys.argv[3])
VOICES = [
    "faber/medium/pt_BR-faber-medium",
    "cadu/medium/pt_BR-cadu-medium",
    "jeff/medium/pt_BR-jeff-medium",
    "edresson/low/pt_BR-edresson-low",
]
BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/"
cache = Path("data/piper_ptbr")
cache.mkdir(parents=True, exist_ok=True)

voices = []
for v in VOICES:
    name = v.split("/")[-1]
    onnx, cfg = cache / f"{name}.onnx", cache / f"{name}.onnx.json"
    try:
        for f, url in ((onnx, BASE + v + ".onnx"), (cfg, BASE + v + ".onnx.json")):
            if not f.exists():
                urllib.request.urlretrieve(url, f)
        voices.append(PiperVoice.load(str(onnx), config_path=str(cfg)))
        print("voz:", name)
    except Exception as e:  # noqa: BLE001
        print("voz indisponível:", name, e)
if not voices:
    sys.exit("Nenhuma voz pt-BR disponível.")

# Variações de entonação; a pontuação muda a prosódia do Piper.
texts = [word, f"{word}.", f"{word}!", f"{word}?", f"{word},", f"Ei, {word}", f"Oi {word}"]
rng = random.Random(42)
test_every = 10  # 10% vai para o conjunto de teste, como no gerador original

for i in range(total):
    voice = rng.choice(voices)
    buf = BytesIO()
    with wave.open(buf, "wb") as w:
        voice.synthesize(
            rng.choice(texts), w,
            length_scale=rng.uniform(0.75, 1.35),
            noise_scale=rng.uniform(0.4, 1.0),
            noise_w=rng.uniform(0.5, 1.1),
        )
    buf.seek(0)
    rate, audio = scipy.io.wavfile.read(buf)
    audio = resample_poly(audio.astype(np.float32), 16000, rate)
    # Volume variado para não deixar todas as amostras iguais.
    audio = np.clip(audio * rng.uniform(0.5, 1.0), -32768, 32767).astype(np.int16)
    split = "positive_test" if i % test_every == 0 else "positive_train"
    out = run_dir / split
    out.mkdir(parents=True, exist_ok=True)
    scipy.io.wavfile.write(out / f"ptbr_{i:05d}.wav", 16000, audio)
    if i % 500 == 0:
        print(f"pt-BR: {i}/{total}")

print(f"pt-BR: {total} amostras com {len(voices)} vozes")
