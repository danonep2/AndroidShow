package com.androidshow.alexa.avs

import com.androidshow.alexa.auth.AuthRepository
import com.androidshow.alexa.auth.Session
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.net.Http
import com.androidshow.alexa.net.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
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

/**
 * Interfaces AVS que o app implementa. Sem publicá-las o AVS aceita o Recognize mas não processa
 * a fala (204 sem diretivas) [Observado no Redmi].
 */
object Capabilities {
    const val WINDOW_TEMPLATE = "fullscreen_mobile"
    const val WINDOW_SIZE = "fullscreen"
    const val INTERACTION_MODE = "mobile_mode"

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
                        put("uiMode", "MOBILE")
                        putJsonObject("interactionDistance") {
                            put("unit", "INCHES")
                            put("value", 18)
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
