# AndroidShow

Cliente Alexa nativo para Android com foco no **modo Show** (tela cheia estilo Echo Show no celular).
Reimplementa o que foi observado em dois clientes existentes:

- **Alexa para Windows** (Microsoft Store): registro do dispositivo, cookies das APIs web e o modo Show
  (`Alexa.Presentation.Home`, APL, TemplateRuntime).
- **Ultimate Alexa** (Android): comunicação AVS `v20160207` (downchannel, eventos multipart, ping).

> Projeto pessoal de interoperabilidade. O app se registra imitando o cliente Windows da Amazon, o que
> não é suportado pela Amazon e pode violar os termos de uso. Use só com a sua própria conta.

## Stack

| Item | Escolha | Motivo |
|---|---|---|
| Linguagem | Kotlin 2.2, coroutines | nativo, sem runtime extra |
| UI | Jetpack Compose (Material 3) | uma só árvore de UI, sem XML de layout |
| Rede | OkHttp 4 (HTTP/2, corpo duplex, `MultipartReader`) | o AVS exige HTTP/2; um só pool de conexões |
| JSON | kotlinx.serialization | sem reflexão, amigável ao R8 |
| DI | manual (`AppGraph`) | zero custo de inicialização |
| Imagens | carregador próprio (OkHttp + LRU) | sem Coil/Glide |
| Segredos | AES-GCM com chave no Android Keystore | sem dependência `androidx.security` |
| Release | R8 completo + shrink de recursos | APK release de ~1,4 MB |

minSdk 26, targetSdk 36.

## Estrutura

```
app/src/main/java/com/androidshow/alexa/
  auth/   login (WebView), registro MAP, renovação de token, armazenamento cifrado
  net/    cliente HTTP compartilhado e utilitários
  avs/    cliente AVS: downchannel, eventos, diretivas, anexos
  voice/  captura de áudio (corpo duplex) e reprodução de fala
  show/   modo Show: tela cheia, cartões, player e APL
  AlexaService.kt  orquestra sessão, diretivas e contexto
  ui/     telas do app
docs/      arquitetura e notas de engenharia reversa
```

## Compilar

Requer JDK 17+ e Android SDK (platform 36).

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Estado

- [x] Etapa 1 — autenticação (login Amazon, `auth/register`, refresh, cookies)
- [x] Etapa 2 — AVS e comandos de voz (Speak, ExpectSpeech, Speaker, SetEndpoint)
- [x] Etapa 3 — modo Show: tela cheia, relógio, cartões TemplateRuntime, player, APL simplificado
- [ ] Etapa 4 — renderizador APL completo e home `Alexa.Presentation.Home` (depende da decompilação do `Alexa.dll`)
- [ ] Etapa 5 — Casa Inteligente via cookies (`api/phoenix`), palavra de ativação

Veja [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Componentes de terceiros

O renderizador APL (Apache 2.0) e os modelos do openWakeWord (CC BY-NC-SA 4.0) têm licenças próprias.
Veja [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
