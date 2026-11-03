#!/usr/bin/env bash
# Prepara o ambiente de treino do openWakeWord dentro do WSL2 (Ubuntu). Roda uma vez só;
# pode ser repetido: cada etapa pula o que já existe.
#
#   bash tools/wakeword/setup.sh            # dataset completo (ACAV100M, ~17 GB)
#   LIGHT=1 bash tools/wakeword/setup.sh    # sem ACAV100M
#
# Tudo fica em $WAKEWORD_HOME (padrão ~/wakeword), no disco do Linux: ler os 17 GB por /mnt/c
# seria muito mais lento.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WW="${WAKEWORD_HOME:-$HOME/wakeword}"
LIGHT="${LIGHT:-0}"
mkdir -p "$WW/data"
cd "$WW"

step() { echo; echo "==> $*"; }

step "Pacotes do sistema"
if ! command -v ffmpeg >/dev/null || ! command -v git >/dev/null; then
    sudo apt-get update -qq
    sudo apt-get install -y -qq git ffmpeg curl ca-certificates build-essential libsndfile1
fi

step "uv e Python 3.10 (as versões fixadas do pipeline não funcionam no 3.12 do Ubuntu)"
if ! command -v uv >/dev/null && [ ! -x "$HOME/.local/bin/uv" ]; then
    curl -LsSf https://astral.sh/uv/install.sh | sh
fi
export PATH="$HOME/.local/bin:$PATH"
[ -d .venv ] || uv venv --python 3.10 .venv
# shellcheck disable=SC1091
source .venv/bin/activate

step "Repositórios"
[ -d openwakeword ] || git clone -q https://github.com/dscripka/openWakeWord openwakeword
[ -d piper-sample-generator ] || git clone -q https://github.com/rhasspy/piper-sample-generator
# O train.py do openWakeWord espera esta versão: as seguintes usam o piper 1.3 e depois viraram pacote.
git -C piper-sample-generator checkout -q 213d4d5
GEN=piper-sample-generator/models/en_US-libritts_r-medium.pt
[ -f "$GEN" ] || curl -L --fail -o "$GEN" \
    https://github.com/rhasspy/piper-sample-generator/releases/download/v2.0.0/en_US-libritts_r-medium.pt

step "Dependências Python (PyTorch com CUDA incluso)"
uv pip install -q torch==2.1.2 torchaudio==2.1.2
uv pip install -q -e ./openwakeword
uv pip install -q \
    "numpy==1.26.4" "scipy==1.11.4" piper-phonemize==1.1.0 webrtcvad \
    mutagen==1.47.0 torchinfo==1.8.0 torchmetrics==1.2.0 speechbrain==0.5.14 \
    audiomentations==0.33.0 torch-audiomentations==0.11.0 acoustics==0.2.6 \
    pronouncing==0.2.0 datasets==2.14.6 "fsspec<=2023.9.2" "pyarrow==14.0.2" deep-phonemizer==0.0.19 \
    pyyaml soundfile piper-tts==1.2.0 onnx onnxruntime "setuptools<70"
python - <<'EOF'
import torch
ok = torch.cuda.is_available()
print("CUDA:", ok, torch.cuda.get_device_name(0) if ok else "(sem GPU: o treino vai ser bem mais lento)")
EOF

step "Modelos de base do openWakeWord (mel + embedding)"
RES=openwakeword/openwakeword/resources/models
mkdir -p "$RES"
for f in embedding_model.onnx melspectrogram.onnx embedding_model.tflite melspectrogram.tflite; do
    [ -f "$RES/$f" ] || curl -sL --fail -o "$RES/$f" "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/$f"
done
# Fonemas para gerar as frases negativas parecidas. O bucket S3 de onde o openWakeWord baixa
# responde 403; este espelho publica o mesmo arquivo com SHA256SUMS.
DP=openwakeword/openwakeword/resources/en_us_cmudict_forward.pt
if [ ! -s "$DP" ] || ! head -c 1 "$DP" | grep -qv '<'; then
    M=https://huggingface.co/TaterTotterson/DeepPhonemizer/resolve/main
    curl -sL --fail -o "$DP" "$M/en_us_cmudict_forward.pt"
    (cd "$(dirname "$DP")" && curl -sL --fail "$M/SHA256SUMS" | grep en_us_cmudict_forward.pt | sha256sum -c -)
fi

step "Dados de treino"
cd data
HF=https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/main
[ -f validation_set_features.npy ] || curl -L --fail -o validation_set_features.npy "$HF/validation_set_features.npy"
if [ "$LIGHT" != "1" ]; then
    F=openwakeword_features_ACAV100M_2000_hrs_16bit.npy
    # -C - retoma o download se cair no meio.
    [ -f "$F.ok" ] || { curl -L --fail -C - -o "$F" "$HF/$F" && touch "$F.ok"; }
fi
python "$HERE/download_audio.py" .

echo
echo "Ambiente pronto em $WW. Próximo passo: bash tools/wakeword/train.sh Gerson"
