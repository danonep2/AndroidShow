"""Baixa os áudios usados no aumento de dados, todos convertidos para wav de 16 kHz mono:

- mit_rirs: respostas ao impulso de salas reais (reverberação);
- audioset_16k: um pedaço do AudioSet (ruídos e sons do dia a dia);
- fma: cerca de 1 hora de música do Free Music Archive.

Uso: python download_audio.py <pasta de dados>. Pula o que já foi baixado.
"""
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

import datasets
import numpy as np
import pyarrow.parquet as pq
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
if not done(audioset, 400):
    audioset.mkdir(exist_ok=True)
    # O AudioSet do Hugging Face hoje vem em parquet (antes eram .tar com .flac); um arquivo
    # tem ~680 MB e cerca de 500 clipes de 10 s.
    parquet = root / "audioset_bal_train_09.parquet"
    if not parquet.exists():
        url = "https://huggingface.co/datasets/agkphysics/AudioSet/resolve/main/data/bal_train/09.parquet"
        print("AudioSet:", url)
        urllib.request.urlretrieve(url, str(parquet) + ".part")
        os.replace(str(parquet) + ".part", parquet)
    table = pq.read_table(parquet, columns=["audio"])
    for i, row in enumerate(tqdm(table.column("audio").to_pylist(), desc="AudioSet → 16 kHz")):
        out = audioset / f"{i:05d}.wav"
        if out.exists() or not row or not row.get("bytes"):
            continue
        # ffmpeg aceita qualquer formato que venha nos bytes (flac, mp3, ogg).
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", "pipe:0", "-ar", "16000", "-ac", "1", str(out)],
                       input=row["bytes"], check=False)

fma = root / "fma"
if not done(fma, 100):
    fma.mkdir(exist_ok=True)
    ds = datasets.load_dataset("rudraml/fma", name="small", split="train", streaming=True)
    ds = iter(ds.cast_column("audio", datasets.Audio(sampling_rate=16000)))
    for i in tqdm(range(3600 // 30), desc="FMA (1 h)"):
        row = next(ds)
        write(fma / f"{i:04d}.wav", row["audio"]["array"])

print("Áudios prontos:", {p.name: len(list(p.glob("*.wav"))) for p in (rirs, audioset, fma)})
