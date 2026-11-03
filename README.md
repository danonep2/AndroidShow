# AndroidShow

Cliente Alexa nativo para Android com foco no **modo Show** (tela cheia estilo Echo Show no celular).
Fala com a Alexa pelo protocolo AVS `v20160207` e desenha as telas com o renderizador APL oficial
de código aberto.

> Projeto pessoal de interoperabilidade, sem relação com a Amazon. O login depende de um perfil de
> dispositivo fornecido por quem compila (veja [Compilar](#compilar)). A Amazon não aceita mais
> registros de produtos Alexa Built-in novos, então usar este app não é suportado por ela e pode
> violar os termos de uso. Use só com a sua própria conta e por sua conta e risco.

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
docs/      arquitetura e notas técnicas
```

## Compilar

Requer JDK 17+ e Android SDK (platform 36).

O perfil de dispositivo usado no registro na Amazon **não vem no projeto**. Defina-o no
`local.properties` (que não vai para o git) ou em variáveis de ambiente `ANDROIDSHOW_<CAMPO>`
(por exemplo `ANDROIDSHOW_DEVICE_TYPE`):

```properties
# obrigatório; sem ele o login fica bloqueado
androidshow.deviceType=<TIPO>
# opcionais; vazios, descrevem o próprio AndroidShow
androidshow.appName=
androidshow.appVersion=
androidshow.deviceModel=
androidshow.osName=
androidshow.osVersion=
```

Escolher e usar um perfil de dispositivo é responsabilidade de quem compila.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Estado

- [x] Etapa 1 — autenticação (login Amazon, `auth/register`, refresh, cookies)
- [x] Etapa 2 — AVS e comandos de voz (Speak, ExpectSpeech, Speaker, SetEndpoint)
- [x] Etapa 3 — modo Show: tela cheia, relógio, cartões TemplateRuntime, player, APL simplificado
- [x] Etapa 4 — renderizador APL oficial e home `Alexa.Presentation.Home`
- [x] Etapa 5 — palavra de ativação local (openWakeWord) e palavras próprias
- [ ] Etapa 6 — alertas e lembretes; Casa Inteligente via cookies (`api/phoenix`)

Veja [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Componentes de terceiros

O renderizador APL (Apache 2.0) e os modelos do openWakeWord (CC BY-NC-SA 4.0) têm licenças próprias.
Veja [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Licença

O código do AndroidShow usa a licença [MIT](LICENSE). Componentes de terceiros mantêm as próprias licenças
(veja acima).
