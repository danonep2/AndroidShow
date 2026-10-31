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
- **Fontes Amazon Ember removidas** (`R.font.*`): `EmbeddedFontResolver` usa a sans-serif do
  sistema no peso pedido.
- **Métodos ausentes**: `Text.getLineBounds`, `Video.setFromEvent` e
  `VideoViewAdapter.inflateViewWithNonZeroDimensions` (caso legado de vídeo, vira no-op).
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
- Viewport `hub`, tema escuro (`res/layout/apl_layout.xml`).
