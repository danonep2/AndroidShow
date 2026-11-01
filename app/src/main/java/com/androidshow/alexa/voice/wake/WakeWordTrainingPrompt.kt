package com.androidshow.alexa.voice.wake

/**
 * Guia para colar numa IA (ChatGPT, Claude etc.) que vai ajudar o usuário a treinar um modelo
 * compatível com o app. [word] entra no texto quando o usuário já digitou o nome no modal.
 */
fun wakeWordTrainingPrompt(word: String): String {
    val w = word.trim().ifEmpty { "<SUA PALAVRA>" }
    return """
Quero treinar um modelo de palavra de ativação (wake word) para a palavra "$w" e usar num app Android que já roda o pipeline do openWakeWord (github.com/dscripka/openWakeWord). Me guie passo a passo. Antes de mandar baixar ou instalar qualquer coisa, me dê um panorama: etapas, requisitos, espaço em disco, tempo estimado e as alternativas (PC local ou Google Colab). Pergunte qual é o meu sistema operacional, a GPU, a RAM e o espaço livre.

## O que o app espera (formato obrigatório)
- Um único arquivo .onnx: o classificador final do openWakeWord, igual ao "alexa_v0.1.onnx" oficial.
- O app já tem o melspectrogram.onnx e o embedding_model.onnx do openWakeWord. NÃO preciso deles; só do modelo da palavra.
- Entrada: um único tensor float32 com forma [1, N, 96] (N vetores de embedding de 96 valores, um a cada 80 ms; o oficial usa N = 16). N precisa ser fixo, não dinâmico.
- Saída: uma probabilidade float32 de 0 a 1, com forma [1, 1].
- Áudio de origem: 16 kHz, mono, 16 bits. O app roda com ONNX Runtime 1.20 no Android, então use um opset que ele suporte (até 21; o padrão de exportação do openWakeWord serve).
- Tamanho típico: algumas centenas de KB (o app aceita até 10 MB).

## Como funciona o treino (resumo)
1. Exemplos positivos sintéticos: milhares de frases com "$w" geradas por TTS (o openWakeWord usa o piper-sample-generator), variando voz, velocidade e entonação. Se a palavra for em português, use vozes pt-BR para a pronúncia sair certa.
2. Aumento de dados: ruído de fundo, música e reverberação (impulse responses de salas) para simular o ambiente real.
3. Exemplos negativos: muitas horas de áudio SEM a palavra (fala comum, música, ruído). O openWakeWord fornece features pré-calculadas do ACAV100M (cerca de 17 GB); é o que mais reduz os disparos falsos. Também gere negativos "parecidos" (palavras que soam perto de "$w").
4. Treino do classificador pequeno sobre os embeddings (script de treino do openWakeWord com um arquivo de configuração .yml), validação e exportação para .onnx.

## Requisitos e custo de processamento
- O treino acontece uma vez por palavra, no PC ou na nuvem. No celular, o custo de detecção não muda.
- GPU NVIDIA com CUDA e 6 a 8 GB de VRAM é o recomendado. Na CPU funciona, mas fica muitas vezes mais lento.
- RAM: 16 GB servem, mas ficam no limite em algumas etapas.
- Disco: de 20 a 30 GB com o dataset completo (ACAV100M + PyTorch com CUDA + áudios gerados); de 5 a 10 GB numa versão leve, sem o ACAV100M, que tende a disparar mais à toa.
- Tempo estimado numa GPU intermediária: geração TTS de 1 a 3 h, aumento de dados de 30 a 60 min, treino de 30 min a 2 h.
- Software: Python 3.10 ou 3.11, PyTorch com CUDA, o repositório do openWakeWord e o piper-sample-generator. No Windows, considere usar o WSL2.
- Alternativa sem instalar nada: o notebook de treino automático do próprio repositório do openWakeWord no Google Colab, com GPU gratuita.

## Dicas para um bom resultado
- Palavras com 3 ou mais sílabas e sons marcantes funcionam melhor. Palavras curtas ou comuns disparam à toa.
- Mire em menos de 0,5 disparo falso por hora e em mais de 70% de acerto na validação.
- No app, o limiar padrão é 0,5. Ajuste depois de testar com a sua voz e o ruído da sua casa.

## O que eu quero no final
O arquivo .onnx exportado, e a confirmação de que a entrada é [1, N, 96] e a saída é [1, 1]. Me dê um script curto em Python com onnxruntime para eu conferir isso antes de importar no app. No app, eu importo o arquivo em Ativação por voz > Adicionar nova palavra.
""".trim()
}
