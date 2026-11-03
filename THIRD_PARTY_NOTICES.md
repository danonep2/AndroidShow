# Componentes de terceiros

O AndroidShow inclui componentes de terceiros. Os textos completos das licenças estão em [licenses/](licenses/).

## APL ViewHost Android e APL Core Library

- **Arquivos:** `app/libs/apl-release.aar`, `app/libs/common-release.aar`, `app/libs/discovery-standard-release.aar`
- **Origem:**
  - [alexa/apl-viewhost-android](https://github.com/alexa/apl-viewhost-android), tag `v2024.3.0`;
  - [alexa/apl-core-library](https://github.com/alexa/apl-core-library), tag `v2024.3.0`, compilada como biblioteca nativa dentro do `apl-release.aar`.
- **Copyright:** Amazon.com, Inc. or its affiliates. All Rights Reserved.
- **Licença:** Apache License 2.0.
  - ViewHost: [LICENSE](licenses/apl-viewhost-android/LICENSE.txt) e [NOTICE](licenses/apl-viewhost-android/NOTICE.txt).
  - Core: [LICENSE](licenses/apl-core-library/LICENSE.txt) e [NOTICE](licenses/apl-core-library/NOTICE.txt).
- **Modificações:** os `.aar` foram compilados a partir do código original com as alterações em [docs/apl/apl-viewhost-android-2024.3.patch](docs/apl/apl-viewhost-android-2024.3.patch). Veja [docs/APL.md](docs/APL.md).

A APL Core Library inclui componentes de terceiros, listados em [THIRD-PARTY-NOTICES.txt](licenses/apl-core-library/THIRD-PARTY-NOTICES.txt):

| Componente | Licença | Observação |
|---|---|---|
| RapidJSON 1.1.0 | MIT | inclui partes sob BSD (msinttypes) e JSON License (dados de teste do jsonchecker) |
| Yoga 1.19.0 | MIT | |
| PEGTL 2.8.3 | MIT | |
| googletest 1.8.1 | BSD-3-Clause | só nos testes do core, não vai no binário |

## openWakeWord

- **Arquivos:** `app/src/main/assets/wakeword/melspectrogram.onnx`, `embedding_model.onnx`, `alexa_v0.1.onnx`
- **Origem:** [dscripka/openWakeWord](https://github.com/dscripka/openWakeWord), de David Scripka.
- **Licença:** o código do openWakeWord usa Apache 2.0, mas os modelos pré-treinados que ele distribui usam [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/). Isso permite só uso não comercial, com atribuição, e obras derivadas desses modelos precisam manter a mesma licença.
- **Modificações:** nenhuma; os arquivos são os originais.
- **Origem do embedding:** o `embedding_model.onnx` deriva do modelo de *speech embedding* do Google, publicado no TFHub sob Apache 2.0.

O código que executa esses modelos ([voice/wake/](app/src/main/java/com/androidshow/alexa/voice/wake/)) reimplementa em Kotlin o pipeline de inferência do openWakeWord.
