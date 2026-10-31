# Arquitetura

Marcação usada aqui e no código: **[Observado]** = visto nos binários analisados;
**[Inferido]** = deduzido, falta confirmar.

## 1. Autenticação

Fluxo de "registro de dispositivo" do MAP (Mobile Authentication Platform) da Amazon, o mesmo que os
apps oficiais usam. O app Windows se registra com o tipo `<REMOVIDO>` [Observado].

1. `DeviceIdentity.generate()` cria:
   - `serial`: 16 bytes aleatórios em hex;
   - `client_id` = hex(`<serial>#<deviceType>`);
   - `code_verifier` PKCE (32 bytes, base64url) e `code_challenge` = S256.
2. `LoginActivity` abre `https://www.<domínio>/ap/signin?...` (OpenID 2.0 + extensão OAuth2,
   `openid.oa2.scope=device_auth_access`, `client_id=device:<client_id>`) num WebView.
   O usuário digita a senha na página da Amazon; o app nunca a vê.
3. A Amazon redireciona para `/ap/maplanding?openid.oa2.authorization_code=...`; o WebView intercepta.
4. `AuthApi.register` → `POST https://api.<domínio>/auth/register` com `registration_data`
   (tipo, serial, versão), `auth_data` (code + verifier) e `requested_token_type`
   `bearer`, `mac_dms`, `website_cookies`. Resposta:
   - `bearer.access_token` (1 h) e `bearer.refresh_token` (longo);
   - `mac_dms.adp_token` + `device_private_key` (assinatura de requisições, ainda não usado);
   - cookies do site e `customer_info`.
5. `AuthRepository.accessToken()` renova via `POST /auth/token` (`source_token_type=refresh_token`)
   quando faltam menos de 2 min, sob um `Mutex` (uma renovação por vez).
6. `AuthRepository.cookies()` troca o refresh token por cookies em `/ap/exchangetoken/cookies`, para as
   APIs de `alexa.<domínio>` (Casa Inteligente, volume etc.).
7. `SecureStore` guarda a `Session` cifrada com AES-256-GCM (chave no Android Keystore).

Pendente de confirmação no `Alexa.dll` (pedido à sessão de análise do app Windows):
`openid.assoc_handle` do Windows (hoje usamos o do iOS, `amzn_dp_project_dee_ios[_tld]`),
`app_name`, `os_version` e `device_model` exatos.

## 2. AVS (etapa 2)

- Host por região: `alexa.{na,eu,fe}.gateway.devices.a2z.com` [Observado no Config.xml do Windows].
- `GET /v20160207/directives`: downchannel HTTP/2 de longa duração, multipart.
- `POST /v20160207/events`: multipart `metadata` (JSON) + `audio` (PCM 16 kHz mono).
  O corpo é *duplex*: o microfone continua enviando enquanto as diretivas (`StopCapture`) chegam.
- `GET /ping` a cada 5 min.

## 3. Modo Show (etapa 3)

Tela cheia que fica ligada, mostra a home multimodal (`Alexa.Presentation.Home`), cartões
`TemplateRuntime` e documentos APL.
