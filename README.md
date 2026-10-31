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
| Segredos | AES-GCM com chave no Android Keystore | sem dependência `androidx.security` |
| Release | R8 completo + shrink de recursos | APK pequeno |

minSdk 26, targetSdk 36.

## Estrutura

```
app/src/main/java/com/androidshow/alexa/
  auth/   login (WebView), registro MAP, renovação de token, armazenamento cifrado
  net/    cliente HTTP compartilhado e utilitários
  avs/    cliente AVS: downchannel, eventos, diretivas, contexto      (etapa 2)
  voice/  captura de áudio e reprodução de fala                       (etapa 2)
  show/   modo Show: tela cheia, cartões e APL                         (etapa 3)
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
- [ ] Etapa 2 — AVS e comandos de voz
- [ ] Etapa 3 — modo Show

Veja [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
