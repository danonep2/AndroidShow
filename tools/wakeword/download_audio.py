"""Baixa os áudios usados no aumento de dados, todos convertidos para wav de 16 kHz mono:

- mit_rirs: respostas ao impulso de salas reais (reverberação);
- audioset_16k: um pedaço do AudioSet (ruídos e sons do dia a dia);
- fma: cerca de 1 hora de música do Free Music Archive.

Uso: python download_audio.py <pasta de dados>. Pula o que já foi baixado.
"""
import os
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

import datasets
import numpy as np
import scipy.io.wavfile
from tqdm import tqdm

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def done(folder: Path, minimum: int) -> bool:
    return folder.exists() and len(list(folder.glob("*.wav"))) >= minimum


def write(path: Path, audio: np.ndarray):
    scipy.io.wavfile.write(path, 16000, (np.clip(audio, -1, 1) * 32767).astype(np.int16))


rirs = root / "mit_rirs"
if not done(rirs, 200):
    rirs.mkdir(exist_ok=True)
    ds = datasets.load_dataset("davidscripka/MIT_environmental_impulse_responses", split="train", streaming=True)
    for row in tqdm(ds, desc="MIT RIR"):
        write(rirs / Path(row["audio"]["path"]).name, row["audio"]["array"])

audioset = root / "audioset_16k"
if not done(audioset, 1000):
    audioset.mkdir(exist_ok=True)
    tar = root / "bal_train09.tar"
    if not tar.exists():
        # O repositório do AudioSet já mudou de estrutura; tenta os dois caminhos conhecidos.
        for url in (
            "https://huggingface.co/datasets/agkphysics/AudioSet/resolve/main/data/bal_train09.tar",
            "https://huggingface.co/datasets/agkphysics/AudioSet/resolve/main/bal_train09.tar",
        ):
            try:
                print("AudioSet:", url)
                urllib.request.urlretrieve(url, tar)
                break
            except Exception as e:  # noqa: BLE001
                print("  falhou:", e)
                tar.unlink(missing_ok=True)
        else:
            sys.exit("Não deu para baixar o AudioSet.")
    raw = root / "audioset_raw"
    with tarfile.open(tar) as t:
        t.extractall(raw)
    for f in tqdm(sorted(raw.rglob("*.flac")), desc="AudioSet → 16 kHz"):
        out = audioset / (f.stem + ".wav")
        if not out.exists():
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(f), "-ar", "16000", "-ac", "1", str(out)], check=False)

fma = root / "fma"
if not done(fma, 100):
    fma.mkdir(exist_ok=True)
    ds = datasets.load_dataset("rudraml/fma", name="small", split="train", streaming=True)
    ds = iter(ds.cast_column("audio", datasets.Audio(sampling_rate=16000)))
    for i in tqdm(range(3600 // 30), desc="FMA (1 h)"):
        row = next(ds)
        write(fma / f"{i:04d}.wav", row["audio"]["array"])

print("Áudios prontos:", {p.name: len(list(p.glob("*.wav"))) for p in (rirs, audioset, fma)})
