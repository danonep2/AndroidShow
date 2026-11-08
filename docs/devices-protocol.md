# Dispositivos: listagem, estado e comandos

APIs web de `alexa.amazon.<domínio>` usadas pela tela de dispositivos ([DevicesApi.kt](../app/src/main/java/com/androidshow/alexa/devices/DevicesApi.kt)).
Não são APIs oficiais nem estáveis. São as mesmas que bibliotecas abertas como `alexapy` e `alexa-remote` usam,
e podem mudar sem aviso.

Legenda:

- **[Confirmado]**: testado no AndroidShow (2026-11-08, conta BR).
- **[Inferido]**: formato conhecido dessas APIs, ainda não testado aqui.

Nenhum token, cookie ou id pessoal aparece abaixo: ids estão mascarados como `<…>`.

**Escopo:** só os dispositivos **já vinculados** à conta: ver a lista, saber o estado de conexão e mandar os
comandos básicos. Descobrir, parear ou adicionar dispositivos e vincular skills está fora do escopo.

## 0. Resumo

| Item | Como funciona |
|---|---|
| Autenticação | **Cookies do site** obtidos com `POST /ap/exchangetoken/cookies` a partir do refresh token do registro MAP, mais o par cookie/header `csrf` |
| Host | `alexa.amazon.<domínio>` da conta (por exemplo `alexa.amazon.com.br`) |
| Lista de casa inteligente | GraphQL `POST /nexus/v1/graphql` (`LegacyAppliances`) |
| Lista de Echos | `GET /api/devices-v2/device` |
| Estado | `POST /api/phoenix/state` com `stateRequests` → `deviceStates[]` (polling) |
| Comando | `PUT /api/phoenix/state` com `controlRequests` → `controlResponses[]` |
| Grupos | `api/phoenix/group` (ainda não testado) |
| Push de estado | Nenhum: a tela consulta de novo ao abrir e depois de cada comando |

## 1. Autenticação

O AndroidShow já faz o registro MAP (`AuthApi.register` → `/auth/register`, renovação em `/auth/token`). Para
estas APIs:

1. `POST /ap/exchangetoken/cookies` troca o refresh token pelos cookies do site (`AuthRepository.cookies()`).
2. Cada requisição leva `Cookie: <cookies do site>; csrf=<n>` e o cabeçalho `csrf: <n>` com o mesmo número
   aleatório. **[Confirmado]**
3. `Accept` e `Content-Type`: `application/json`.

O token LWA do AVS não serve para essas APIs.

## 2. Listar dispositivos

### 2.1 Casa inteligente: GraphQL Nexus

`POST https://alexa.amazon.<domínio>/nexus/v1/graphql` com `{"query": "<texto>", "variables": {}}`. **[Confirmado]**

```graphql
query LegacyAppliances {
  endpoints(endpointsQueryParams: { paginationParams: { disablePagination: true } }) {
    items {
      legacyAppliance { applianceId applianceTypes applianceNetworkState isEnabled friendlyName entityId actions capabilities }
    }
  }
}
```

Resposta (resumida):

```json
{ "data": { "endpoints": { "items": [ {
  "legacyAppliance": {
    "applianceId": "<skill>_<…>",
    "entityId": "<uuid>",
    "friendlyName": "Luz da sala",
    "applianceTypes": ["LIGHT"],
    "isEnabled": true,
    "applianceNetworkState": { "reachability": "REACHABLE", "lastSeenAt": "…", "createdAt": "…" },
    "capabilities": [ { "interfaceName": "Alexa.PowerController", "…": "…" },
                      { "interfaceName": "Alexa.BrightnessController", "…": "…" } ],
    "actions": []
  } } ] } } }
```

- `applianceNetworkState` vem como objeto (`reachability`, `lastSeenAt`, `createdAt`). **[Confirmado]**
- `actions` vem vazio; as ações possíveis saem de `capabilities[].interfaceName`. **[Confirmado]**
- Tipos comuns: `LIGHT`, `SMARTPLUG`, `SWITCH`, `THERMOSTAT`, `SMARTLOCK`, `SCENE_TRIGGER`, `ACTIVITY_TRIGGER`.

### 2.2 Echos e outros dispositivos com Alexa

`GET https://alexa.amazon.<domínio>/api/devices-v2/device` **[Confirmado]**. Cada item de `devices[]` traz
`serialNumber`, `deviceType`, `accountName`, `deviceFamily`, `capabilities` (por exemplo `VOLUME_SETTING`) e
`online: true|false`.

## 3. Ler o estado

`POST https://alexa.amazon.<domínio>/api/phoenix/state` **[Confirmado]**

```json
{ "stateRequests": [ { "entityId": "<applianceId>", "entityType": "APPLIANCE" } ] }
```

**Importante:** apesar do nome do campo, o valor precisa ser o **`applianceId`**. Com o `entityId` do Nexus a
resposta é `TargetApplianceNotFoundException`. **[Confirmado]**

```json
{ "deviceStates": [ {
    "entity": { "entityId": "<applianceId>", "entityType": "APPLIANCE" },
    "capabilityStates": [
      "{\"namespace\":\"Alexa.PowerController\",\"name\":\"powerState\",\"value\":\"ON\",…}",
      "{\"namespace\":\"Alexa.EndpointHealth\",\"name\":\"connectivity\",\"value\":{\"value\":\"OK\"},…}" ] } ],
  "errors": [ { "entity": { "entityId": "<…>", "entityType": "APPLIANCE" }, "code": "ENDPOINT_UNREACHABLE", "message": "…" } ] }
```

Cada item de `capabilityStates` é uma **string JSON** que precisa de um segundo parse.

### 3.1 Estado de conexão

| Origem | Estado na tela |
|---|---|
| `isEnabled == false` | desativado |
| `reachability == NOT_REACHABLE` ou `connectivity == UNREACHABLE` | offline |
| `errors[].code == ENDPOINT_UNREACHABLE` | sem resposta |
| `devices-v2` com `online: false` | offline (Echos) |

Não há push de estado pelo downchannel AVS: a tela consulta de novo ao abrir e depois de cada comando.

## 4. Comandos

`PUT https://alexa.amazon.<domínio>/api/phoenix/state` **[Confirmado para `turnOn`, `turnOff` e `setBrightness`]**

```json
{ "controlRequests": [ {
    "entityId": "<applianceId>",
    "entityType": "APPLIANCE",
    "parameters": { "action": "setBrightness", "brightness": 40 } } ] }
```

Resposta: `{ "controlResponses": [ { "entityId": "<…>", "code": "SUCCESS" } ], "errors": [] }`.

Outras ações **[Inferido]**:

| Controle | `action` | Parâmetros |
|---|---|---|
| Cor | `setColor` | `colorName` (por exemplo `red`, `blue`) |
| Temperatura de cor | `setColorTemperature` | `colorTemperatureName` (por exemplo `warm_white`) |
| Percentual | `setPercentage` | `percentage` |
| Termostato | `setTargetTemperature` | `targetTemperature.value`, `targetTemperature.scale` (`CELSIUS`/`FAHRENHEIT`) |
| Modo do termostato | `setThermostatMode` | `thermostatMode.value` |
| Trava | `lockAction` | `targetLockState.value` (`LOCKED`/`UNLOCKED`) |
| Grupo | `setGroupPowerState` | `entityType: "GROUP"` com o `groupId` |

### 4.1 Volume de Echo **[Inferido]**

`GET api/devices/<deviceType>/<serialNumber>/audio/v2/volume` para ler e `PUT …/audio/v2/speakerVolume` para
definir, com `deviceType` e `serialNumber` vindos do `devices-v2`. Os campos do corpo ainda precisam ser
confirmados.

### 4.2 Anúncios e rotinas

Não há API simples para isso aqui. O caminho mais direto é a voz via AVS ("Alexa, anuncie…", "Alexa, boa noite").

## 5. Grupos e cômodos **[Inferido]**

`GET api/phoenix/group` lista `applianceGroups[]` com `groupId`, `name` e `applianceIds[]`. Os cômodos da Alexa
são grupos. Ainda não testado.

## 6. Limites e riscos

| Ponto | Situação |
|---|---|
| Região | Usar o host e os cookies do domínio da conta (`exchangetoken` com o domínio da loja). |
| Limites de taxa | Não documentados. Manter a cadência baixa: consultar só ao abrir a tela e depois de comandos. |
| Estabilidade | APIs internas do site; podem mudar sem aviso. |
| Alternativa | A voz por AVS ("ligar a luz da sala") funciona para todos os comandos e não depende dessas APIs. |
