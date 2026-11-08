# Renderizador APL

O modo Show desenha documentos APL (home do Echo Show e respostas de skills) com o renderizador
oficial e open source da Amazon, **apl-viewhost-android 2024.3** (motor **apl-core** em C++,
Apache 2.0). Não há pacote publicado, então os AARs são compilados localmente e versionados em
`app/libs/` (somente `arm64-v8a`):

| AAR | Conteúdo |
|---|---|
| `apl-release.aar` | viewhost Java + `libapl-jni.so` (motor apl-core estático) |
| `common-release.aar` | utilitários + `libcommon-jni.so` |
| `discovery-standard-release.aar` | descoberta de extensões |

## Como recompilar

Requisitos: JDK 17, Android SDK 34, NDK `23.0.7599858`, CMake `3.18.1` (traz `ninja`) e um
compilador C++ do host (o build gera a ferramenta `enumgen` para o Windows; usamos WinLibs GCC).

```bash
git clone --depth 1 --branch v2024.3.0 https://github.com/alexa/apl-core-library.git APLCoreEngine
git clone --depth 1 --branch v2024.3.0 https://github.com/alexa/apl-viewhost-android.git APLViewhostAndroid

# 1. Motor C++ publicado no Maven local (com.amazon.apl.android:coreengine)
cd APLCoreEngine/android   # copiar gradle/wrapper do APLViewhostAndroid
./gradlew :coreengine:publishToMavenLocal -Pandroid.injected.build.abi=arm64-v8a

# 2. Viewhost, com o patch deste repositório
cd ../../APLViewhostAndroid
git apply ../AndroidShow/docs/apl/apl-viewhost-android-2024.3.patch
./gradlew :apl:assembleRelease :common:assembleRelease :discovery:assembleStandardRelease \
  -Pandroid.injected.build.abi=arm64-v8a
```

## O que o patch corrige

O repositório público (tag e `main`) não compila sem ajustes:

- **buildSrc ausente**: `APLPlugin` e `EnumGenerator` são internos da Amazon. O patch recria
  versões mínimas (os enums gerados já estão versionados).
- **Fontes Amazon Ember removidas** (`R.font.*`): `EmbeddedFontResolver` usa as famílias nomeadas do
  sistema (`sans-serif-light`, `sans-serif`, `sans-serif-medium`, negrito) e passa a ser o resolvedor
  principal: o de runtime lê o `fonts.xml` e no HyperOS devolvia faces inclinadas. Itálico ignorado.
- **Métodos ausentes**: `Text.getLineBounds`, `Video.setFromEvent` e
  `VideoViewAdapter.inflateViewWithNonZeroDimensions` (caso legado de vídeo, vira no-op).
- **Eventos sem tratamento**: `buildEvent` não ligava PlayMedia, ControlMedia, RequestLineBounds,
  Preroll e Speak (NPE a cada frame). Os quatro primeiros usam as classes existentes; Preroll e
  Speak (SpeakItem) se resolvem na hora, já que o app não tem TTS do APL. Com os enums corrigidos
  (item abaixo), esses tipos não chegam mais do core; os casos ficam só para o código compilar.
- **Enums fora de sincronia com o core**: os enums Java versionados no viewhost não batem com o
  apl-core público 2024.3 e o Java lia o número de outra propriedade ou evento. O patch traz
  `ComponentType`, `EventMediaType`, `EventType`, `PropertyKey` e `SpanType` gerados pelo `enumgen`
  do próprio APLCoreEngine, com os mesmos filtros do `android/coreengine/CMakeLists.txt`.
  - `PropertyKey`: faltava `kPropertyOnCursorMove`. O `kPropertyVisualHash` era lido vazio, e
    todos os textos de um documento caíam na mesma chave do cache de layout. Saíam o mesmo texto
    curto, com o tamanho e a cor do primeiro texto medido.
  - `EventType`: o Java tinha cinco tipos a mais no começo (ControlMedia, PlayMedia, Preroll,
    RequestFirstLineBounds, Speak), então todo evento era lido como outro. SendEvent era lido como
    Preroll, por isso nunca chegava ao app. Os tipos ausentes no core ficam com ids 1000+.
  - `SpanType`: negrito, itálico e os outros estilos de texto estavam deslocados em um.
  - `EventMediaType`: o core não tem `Video`, que fica com id 1000.

  Para conferir depois de atualizar o core, gere os enums com o `enumgen` e compare com
  `apl/src/main/java/com/amazon/apl/enums` (o `enumgen` no Windows grava CRLF; converta para LF).
- **Classe duplicada**: `devtools.controllers.DTConnection` antiga removida em favor de `impl`.
- `settings.gradle` reduzido aos módulos necessários.

No app, o manifesto remove as permissões de extensões Alexa declaradas pelo AAR: elas pertencem ao
app Alexa oficial e impediam a instalação quando ele está no aparelho.

## Integração

- `show/apl/Apl.kt`: inicialização em segundo plano, carregador de pacotes (URL do `import` ou
  CDN pública `arl.assets.apl-alexa.com`, com cache) e o composable `AplDocument`.
- Home: o documento SPA recebe `payload = { items: [item do UpdateData para cada datasources.items[i]] }`,
  como o `AssembleSPADocumentAndData` do app Windows.
- Skills: cada parâmetro do `mainTemplate` recebe o datasource de mesmo nome (ou todos, para
  `payload`); `SendEvent` vira `Alexa.Presentation.APL.UserEvent`.
- Viewport `hub`, tema escuro (`res/layout/apl_layout.xml`). O celular deitado tem ~853×384 dp,
  menor que qualquer Echo Show; o scaling do APL monta o documento no perfil `hubLandscapeLarge`
  (Echo Show 8, 1280–1919×800 dp) e reduz para caber (~0,48×). Com o perfil do Echo Show 5
  (480 dp de altura) os layouts ficavam grandes e cortados.
- `{WakeWord}` nas dicas da home é trocado por "Alexa" antes de desenhar, como no app Windows.
