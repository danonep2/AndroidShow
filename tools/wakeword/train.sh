#!/usr/bin/env bash
# Treina um modelo de palavra de ativação compatível com o app (classificador do openWakeWord).
#
#   bash tools/wakeword/train.sh Gerson
#   SPELLINGS="zhair son,zher sohn" bash tools/wakeword/train.sh Gerson
#
# O gerador de vozes do openWakeWord é em inglês: SPELLINGS são grafias que fazem essas vozes
# falarem a palavra perto da pronúncia brasileira (o padrão é a própria palavra). Além delas,
# entram amostras de vozes pt-BR do Piper (ptbr_samples.py).
#
# Variáveis opcionais: N_SAMPLES (padrão 25000), STEPS (50000), N_PTBR (3000).
# Saída: tools/wakeword/out/<palavra>.onnx, pronto para importar no app.
set -euo pipefail

WORD="${1:?uso: train.sh <Palavra>}"
NAME="$(echo "$WORD" | tr '[:upper:]' '[:lower:]' | tr ' ' '_')"
SPELLINGS="${SPELLINGS:-$WORD}"
N_SAMPLES="${N_SAMPLES:-25000}"
STEPS="${STEPS:-50000}"
N_PTBR="${N_PTBR:-3000}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WW="${WAKEWORD_HOME:-$HOME/wakeword}"

cd "$WW"
# shellcheck disable=SC1091
source .venv/bin/activate
mkdir -p runs
CFG="runs/$NAME.yml"
LOG="runs/$NAME.log"
exec > >(tee -a "$LOG") 2>&1
echo "==> $(date '+%F %T') treinando \"$WORD\" (grafias: $SPELLINGS; $N_SAMPLES amostras, $STEPS passos)"

python - "$CFG" "$NAME" "$SPELLINGS" "$N_SAMPLES" "$STEPS" <<'EOF'
import sys, yaml
from pathlib import Path
cfg_path, name, spellings, n, steps = sys.argv[1:]
c = yaml.safe_load(open("openwakeword/examples/custom_model.yml"))
c.update({
    "model_name": name,
    "target_phrase": [s.strip() for s in spellings.split(",") if s.strip()],
    # Nomes parecidos em inglês e português: o modelo aprende a não disparar com eles.
    "custom_negative_phrases": ["person", "jason", "nelson", "anderson", "emerson", "jefferson",
                                "version", "gershwin", "pessoa", "versão", "Jéssica", "certo"],
    "n_samples": int(n),
    "n_samples_val": max(1000, int(n) // 10),
    "steps": int(steps),
    "target_accuracy": 0.7,
    "target_recall": 0.5,
    "target_false_positives_per_hour": 0.5,
    "output_dir": "runs",
    "piper_sample_generator_path": "./piper-sample-generator",
    "rir_paths": ["data/mit_rirs"],
    "background_paths": ["data/audioset_16k", "data/fma"],
    "background_paths_duplication_rate": [1, 1],
    "false_positive_validation_data_path": "data/validation_set_features.npy",
    "feature_data_files": {"ACAV100M_sample": "data/openwakeword_features_ACAV100M_2000_hrs_16bit.npy"}
        if Path("data/openwakeword_features_ACAV100M_2000_hrs_16bit.npy.ok").exists() else {},
    # 8 GB de VRAM: lotes menores que o padrão.
    "tts_batch_size": 25,
    "augmentation_batch_size": 16,
})
yaml.safe_dump(c, open(cfg_path, "w"), allow_unicode=True)
print("config:", cfg_path, "| ACAV100M:", bool(c["feature_data_files"]))
EOF

TRAIN=openwakeword/openwakeword/train.py
t0=$SECONDS
lap() { echo "==> $1 em $(( (SECONDS - t0) / 60 )) min"; t0=$SECONDS; }

python "$TRAIN" --training_config "$CFG" --generate_clips
lap "falas sintéticas (inglês) geradas"

python "$HERE/ptbr_samples.py" "$WORD" "runs/$NAME" "$N_PTBR"
lap "falas pt-BR geradas"

python "$TRAIN" --training_config "$CFG" --augment_clips
lap "aumento de dados"

# No fim, o train.py tenta converter para .tflite; o app não usa e a conversão pode falhar.
python "$TRAIN" --training_config "$CFG" --train_model || [ -f "runs/$NAME.onnx" ]
lap "treino"

python "$HERE/check_model.py" "runs/$NAME.onnx" "runs/$NAME/positive_test"
mkdir -p "$HERE/out"
cp "runs/$NAME.onnx" "$HERE/out/$NAME.onnx"
echo "==> $(date '+%F %T') pronto: tools/wakeword/out/$NAME.onnx (log em $WW/$LOG)"
