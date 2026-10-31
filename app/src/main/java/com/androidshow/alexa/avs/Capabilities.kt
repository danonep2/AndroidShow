package com.androidshow.alexa.avs

import com.androidshow.alexa.auth.AuthRepository
import com.androidshow.alexa.auth.Session
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.net.Http
import com.androidshow.alexa.net.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Interfaces AVS que o app implementa. Sem publicá-las o AVS aceita o Recognize mas não processa
 * a fala (204 sem diretivas) [Observado no Redmi].
 */
object Capabilities {
    const val WINDOW_TEMPLATE = "fullscreen_hub"
    const val WINDOW_SIZE = "fullscreen"
    // HUB: o Echo Show se anuncia assim; MOBILE fazia a nuvem responder só em voz como no app Alexa [Observado].
    const val INTERACTION_MODE = "hub_mode"

    fun list(widthPx: Int, heightPx: Int): JsonArray = buildJsonArray {
        iface("SpeechRecognizer", "2.3") {
            putJsonObject("configurations") {
                putJsonArray("wakeWords") {
                    addJsonObject {
                        putJsonArray("scopes") { add("DEFAULT") }
                        putJsonArray("values") { add(buildJsonArray { add("ALEXA") }) }
                    }
                }
            }
        }
        iface("SpeechSynthesizer", "1.3")
        iface("AudioPlayer", "1.4")
        iface("Alerts", "1.4")
        iface("Speaker", "1.0")
        iface("System", "2.0")
        iface("TemplateRuntime", "1.2")
        iface("Alexa.Display.Window", "1.0") {
            putJsonObject("configurations") {
                putJsonArray("templates") {
                    addJsonObject {
                        put("id", WINDOW_TEMPLATE)
                        put("type", "STANDARD")
                        putJsonObject("configuration") {
                            putJsonArray("sizes") {
                                addJsonObject {
                                    put("type", "DISCRETE")
                                    put("id", WINDOW_SIZE)
                                    putJsonObject("value") {
                                        put("unit", "PIXEL")
                                        putJsonObject("value") {
                                            put("width", widthPx)
                                            put("height", heightPx)
                                        }
                                    }
                                }
                            }
                            putJsonArray("interactionModes") { add(INTERACTION_MODE) }
                        }
                    }
                }
            }
        }
        iface("Alexa.InteractionMode", "1.1") {
            putJsonObject("configurations") {
                putJsonArray("interactionModes") {
                    addJsonObject {
                        put("id", INTERACTION_MODE)
                        put("uiMode", "HUB")
                        putJsonObject("interactionDistance") {
                            put("unit", "INCHES")
                            put("value", 36)
                        }
                        put("touch", "SUPPORTED")
                        put("keyboard", "UNSUPPORTED")
                        put("video", "SUPPORTED")
                        put("dialog", "SUPPORTED")
                    }
                }
            }
        }
        iface("Alexa.Presentation", "1.0")
        iface("Alexa.Presentation.APL", "1.4") {
            putJsonObject("configurations") {
                putJsonObject("runtime") { put("maxVersion", "2023.3") }
            }
        }
    }

    /** PUT /v1/devices/@self/capabilities (204 = aceito) [Observado: aceito para <REMOVIDO>]. */
    suspend fun publish(http: OkHttpClient, auth: AuthRepository, s: Session, caps: JsonArray): Int {
        val body = buildJsonObject {
            put("envelopeVersion", "20160207")
            put("capabilities", caps)
        }
        val request = Request.Builder()
            .url("https://${s.marketplace.region.alexaApi}/v1/devices/@self/capabilities")
            .header("Authorization", "Bearer ${auth.accessToken()}")
            .put(body.toString().toRequestBody(Http.JSON))
            .build()
        return http.newCall(request).await().use { r ->
            DiagLog.i("caps", "PUT capabilities → ${r.code} ${r.body?.string().orEmpty().take(1500)}")
            r.code
        }
    }

    /**
     * Evento Alexa.Discovery.AddOrUpdateReport (modelo de endpoints), que o app Windows usa
     * (`SendAddOrUpdateReportEvent` no Alexa.dll) [Observado]. Vai sem contexto: o envelope v3
     * recusa o contexto em lista dos eventos v20160207 [Observado: 400 INVALID_REQUEST_EXCEPTION].
     */
    fun discoveryEvent(s: Session, caps: JsonArray, token: String): JsonObject = buildJsonObject {
        putJsonObject("header") {
            put("namespace", "Alexa.Discovery")
            put("name", "AddOrUpdateReport")
            put("payloadVersion", "3")
            put("messageId", UUID.randomUUID().toString())
            put("eventCorrelationToken", UUID.randomUUID().toString())
        }
        putJsonObject("payload") {
            putJsonArray("endpoints") {
                addJsonObject {
                    // [Inferido] formato dos SDKs AVS é clientId::productId::serial; aqui tipo::serial.
                    put("endpointId", endpointId(s))
                    put("manufacturerName", "AndroidShow")
                    put("description", "AndroidShow")
                    put("friendlyName", s.deviceName ?: "AndroidShow")
                    putJsonArray("displayCategories") { add("ALEXA_VOICE_ENABLED") }
                    putJsonObject("registration") {
                        put("productId", s.deviceType)
                        put("deviceSerialNumber", s.deviceSerial)
                    }
                    put("capabilities", caps)
                }
            }
            putJsonObject("scope") {
                put("type", "BearerToken")
                put("token", token)
            }
        }
    }

    fun endpointId(s: Session) = "${s.deviceType}::${s.deviceSerial}"

    private fun JsonArrayBuilder.iface(
        name: String,
        version: String,
        extra: JsonObjectBuilder.() -> Unit = {},
    ) = addJsonObject {
        put("type", "AlexaInterface")
        put("interface", name)
        put("version", version)
        extra()
    }
}
