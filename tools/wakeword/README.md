# Treino de palavra de ativação

Gera o classificador `.onnx` do openWakeWord que o app importa em **Ativação por voz › Adicionar nova palavra**.
Roda no WSL2 (Ubuntu 24.04) com GPU NVIDIA.

```bash
bash tools/wakeword/setup.sh          # uma vez: Python 3.10, PyTorch com CUDA, ~22 GB de dados
# Grafias escolhidas comparando os fonemas do espeak: en-us "zhairsong" = ʒˈɛɹsɔŋ, perto do pt-BR ʒˈɛɾsõ.
SPELLINGS="zhairsong,zhair sohn,zhersown,zhare sone" bash tools/wakeword/train.sh Gerson
```

Resultado em `tools/wakeword/out/<palavra>.onnx`. Dados, ambiente e logs ficam em `~/wakeword`, dentro do WSL.

| Etapa | O que faz |
|---|---|
| `--generate_clips` | Falas sintéticas com as vozes em inglês do piper-sample-generator. Cada grafia em `SPELLINGS` aproxima a pronúncia brasileira. |
| `ptbr_samples.py` | Mais falas com 4 vozes pt-BR do Piper. |
| `--augment_clips` | Mistura ruído, música e reverberação, depois calcula os embeddings. |
| `--train_model` | Treina o classificador contra os negativos do ACAV100M e exporta o `.onnx`. |
| `check_model.py` | Confere o formato aceito pelo app e o acerto nas falas de teste. |

O `train.sh` aceita estas variáveis:

- `N_SAMPLES`: padrão 25000;
- `STEPS`: padrão 50000;
- `N_PTBR`: padrão 3000.

O limite de RAM do WSL fica em `%UserProfile%\.wslconfig`, configurado com 12 GB e 16 GB de swap.
