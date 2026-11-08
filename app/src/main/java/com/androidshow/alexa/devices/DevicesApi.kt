package com.androidshow.alexa.devices

import com.androidshow.alexa.auth.AuthRepository
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.net.Http
import com.androidshow.alexa.net.HttpException
import com.androidshow.alexa.net.arr
import com.androidshow.alexa.net.awaitString
import com.androidshow.alexa.net.obj
import com.androidshow.alexa.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.random.Random

/** Dispositivo vinculado à conta: um Echo (devices-v2) ou um aparelho de casa inteligente (Nexus). */
data class Device(
    val id: String,
    val name: String,
    val kind: Kind,
    /** Família (ECHO, KNIGHT…) ou tipos do aparelho (LIGHT, SMARTPLUG…). */
    val type: String,
    /** null quando a resposta não diz. */
    val online: Boolean?,
    val enabled: Boolean = true,
    /** Ações aceitas pelo aparelho (turnOn, setBrightness…). Vazio para Echos. */
    val actions: Set<String> = emptySet(),
    val power: Boolean? = null,
    /** Brilho 0–100, quando o aparelho informa. */
    val brightness: Int? = null,
    /** Cor atual em HSB (matiz 0–360, saturação 0–1), quando o aparelho informa. */
    val hue: Float? = null,
    val saturation: Float? = null,
    val colorTemperatureK: Int? = null,
) {
    enum class Kind { Echo, SmartHome }

    val canPower get() = "turnOn" in actions && "turnOff" in actions
    val canDim get() = "setBrightness" in actions
    val canColor get() = "setColor" in actions
    val canColorTemperature get() = "setColorTemperature" in actions
    val isLight get() = "LIGHT" in type
}

/** Estado lido em api/phoenix/state. Campos null quando a resposta não traz. */
data class ApplianceState(
    val power: Boolean? = null,
    val brightness: Int? = null,
    val online: Boolean? = null,
    val hue: Float? = null,
    val saturation: Float? = null,
    val colorTemperatureK: Int? = null,
)

/**
 * APIs web de alexa.<domínio>, com os cookies do site (registro MAP) e o par cookie/header csrf.
 * Protocolo em docs/devices-protocol.md.
 */
class DevicesApi(private val http: OkHttpClient, private val auth: AuthRepository) {
    /** Valor qualquer, repetido no cookie e no header: o servidor só compara os dois. */
    private val csrf = Random.nextInt(100_000_000, 999_999_999).toString()

    /** GET /api/devices-v2/device: Echos e outros aparelhos com Alexa. */
    suspend fun echos(): List<Device> {
        val json = request("echos", "GET", "/api/devices-v2/device?cached=true")
        return json.arr("devices").orEmpty().mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val serial = d.str("serialNumber") ?: return@mapNotNull null
            Device(
                id = serial,
                name = d.str("accountName") ?: serial,
                kind = Device.Kind.Echo,
                type = d.str("deviceFamily").orEmpty(),
                online = (d["online"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
    }

    /** POST /nexus/v1/graphql (LegacyAppliances): aparelhos de casa inteligente. */
    suspend fun appliances(): List<Device> {
        val query = buildJsonObject { put("query", LEGACY_APPLIANCES); putJsonObject("variables") {} }
        val json = request("appliances", "POST", "/nexus/v1/graphql", query)
        val items = json.obj("data")?.obj("endpoints")?.arr("items").orEmpty()
        return items.mapNotNull { e ->
            val a = (e as? JsonObject)?.obj("legacyAppliance") ?: return@mapNotNull null
            val types = a.arr("applianceTypes").orEmpty().map { it.jsonPrimitive.content }
            // Os Echos também aparecem aqui como ALEXA_VOICE_ENABLED; já vêm do devices-v2.
            if ("ALEXA_VOICE_ENABLED" in types) return@mapNotNull null
            Device(
                // phoenix/state responde TargetApplianceNotFoundException para o entityId: usa o applianceId.
                id = a.str("applianceId") ?: return@mapNotNull null,
                name = a.str("friendlyName").orEmpty(),
                kind = Device.Kind.SmartHome,
                type = types.joinToString(", "),
                online = reachability(a["applianceNetworkState"]),
                enabled = (a["isEnabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                actions = a.arr("actions").orEmpty().map { it.jsonPrimitive.content }.toSet() + actionsOf(a["capabilities"]),
            )
        }
    }

    /** POST api/phoenix/state com stateRequests: liga/desliga, brilho e conectividade de cada entityId. */
    suspend fun states(entityIds: List<String>): Map<String, ApplianceState> {
        if (entityIds.isEmpty()) return emptyMap()
        val body = buildJsonObject {
            putJsonArray("stateRequests") {
                entityIds.forEach { id -> addJsonObject { put("entityId", id); put("entityType", "APPLIANCE") } }
            }
        }
        val json = request("state", "POST", "/api/phoenix/state", body)
        val result = mutableMapOf<String, ApplianceState>()
        json.arr("deviceStates").orEmpty().forEach { e ->
            val d = e as? JsonObject ?: return@forEach
            val id = d.obj("entity")?.str("entityId") ?: return@forEach
            var s = ApplianceState()
            d.arr("capabilityStates").orEmpty().forEach { c ->
                // Cada item é uma string JSON.
                val cap = runCatching { Http.json.parseToJsonElement(c.jsonPrimitive.content).jsonObject }.getOrNull()
                    ?: return@forEach
                val value = cap["value"]
                val prim = value as? JsonPrimitive
                s = when (cap.str("name")) {
                    "powerState" -> s.copy(power = prim?.content == "ON")
                    "brightness" -> s.copy(brightness = prim?.intOrNull)
                    "connectivity" -> s.copy(online = ((value as? JsonObject)?.str("value") ?: prim?.content) == "OK")
                    "color" -> (value as? JsonObject).let { v ->
                        s.copy(hue = v?.str("hue")?.toFloatOrNull(), saturation = v?.str("saturation")?.toFloatOrNull())
                    }
                    "colorTemperatureInKelvin" -> s.copy(colorTemperatureK = prim?.intOrNull)
                    else -> s
                }
            }
            result[id] = s
        }
        json.arr("errors").orEmpty().forEach { e ->
            val err = e as? JsonObject ?: return@forEach
            val id = err.obj("entity")?.str("entityId") ?: return@forEach
            DiagLog.w("devices", "estado de $id: ${err.str("code")}")
            if (err.str("code") == "ENDPOINT_UNREACHABLE") result[id] = ApplianceState(null, null, online = false)
        }
        return result
    }

    /**
     * PUT api/phoenix/state com controlRequests. [parameters] leva a ação, ex.:
     * `action=turnOn` ou `action=setBrightness, brightness=40`. Lança se a resposta trouxer erro.
     */
    suspend fun control(entityId: String, parameters: Map<String, Any>) {
        val body = buildJsonObject {
            putJsonArray("controlRequests") {
                addJsonObject {
                    put("entityId", entityId)
                    put("entityType", "APPLIANCE")
                    putJsonObject("parameters") {
                        parameters.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) }
                    }
                }
            }
        }
        val json = request("control", "PUT", "/api/phoenix/state", body)
        val error = json.arr("errors").orEmpty().firstOrNull() as? JsonObject
        if (error != null) throw IllegalStateException(error.str("code") ?: error.toString())
    }

    /**
     * `actions` vem vazio em contas novas; as ações saem das interfaces em `capabilities`
     * (lista de objetos com interfaceName, ou de strings JSON).
     */
    private fun actionsOf(capabilities: JsonElement?): Set<String> {
        val interfaces = (capabilities as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { c ->
            val obj = c as? JsonObject
                ?: runCatching { Http.json.parseToJsonElement(c.jsonPrimitive.content).jsonObject }.getOrNull()
            obj?.str("interfaceName")
        }
        return buildSet {
            if ("Alexa.PowerController" in interfaces) { add("turnOn"); add("turnOff") }
            if ("Alexa.BrightnessController" in interfaces) add("setBrightness")
            if ("Alexa.ColorController" in interfaces) add("setColor")
            if ("Alexa.ColorTemperatureController" in interfaces) add("setColorTemperature")
        }
    }

    /** reachability vem como objeto; aceita também string JSON por segurança. */
    private fun reachability(state: JsonElement?): Boolean? {
        val obj = when (state) {
            is JsonObject -> state
            is JsonPrimitive -> runCatching { Http.json.parseToJsonElement(state.content).jsonObject }.getOrNull()
            else -> null
        } ?: return null
        return when (obj.str("reachability")) {
            "REACHABLE" -> true
            "NOT_REACHABLE", "UNREACHABLE" -> false
            else -> null
        }
    }

    /** Em 401/403 troca os cookies uma vez e repete. Guarda a resposta em dump-devices-<name>.json. */
    private suspend fun request(name: String, method: String, path: String, json: JsonObject? = null): JsonObject {
        val body = json?.toString()?.toRequestBody(Http.JSON)
        val text = try {
            execute(method, path, body, forceCookies = false)
        } catch (e: HttpException) {
            if (e.code != 401 && e.code != 403) throw e
            DiagLog.w("devices", "$name: HTTP ${e.code}, renovando cookies")
            execute(method, path, body, forceCookies = true)
        }
        DiagLog.dump("devices-$name", text)
        return if (text.isBlank()) JsonObject(emptyMap()) else Http.json.parseToJsonElement(text).jsonObject
    }

    private suspend fun execute(method: String, path: String, body: RequestBody?, forceCookies: Boolean): String {
        val host = auth.session.value?.marketplace?.alexaWebHost ?: throw IllegalStateException("Não autenticado")
        val cookies = auth.cookies(force = forceCookies) + ("csrf" to csrf)
        val request = Request.Builder()
            .url("https://$host$path")
            .header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            .header("csrf", csrf)
            .header("Accept", "application/json; charset=utf-8")
            .method(method, body)
            .build()
        return http.newCall(request).awaitString()
    }

    private companion object {
        val LEGACY_APPLIANCES = """
            query LegacyAppliances {
              endpoints(endpointsQueryParams: { paginationParams: { disablePagination: true } }) {
                items {
                  legacyAppliance { applianceId applianceTypes applianceNetworkState isEnabled friendlyName entityId actions capabilities }
                }
              }
            }
        """.trimIndent()
    }
}
