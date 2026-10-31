package com.androidshow.alexa.show

import android.util.Log
import com.androidshow.alexa.avs.Directive
import com.androidshow.alexa.avs.avsState
import com.androidshow.alexa.net.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** O que a tela do modo Show está mostrando. */
sealed interface ShowScreen {
    /** Tela de descanso (relógio). [home] guarda o último payload Alexa.Presentation.Home recebido. */
    data class Home(val home: JsonObject? = null) : ShowScreen

    /** Cartão TemplateRuntime.RenderTemplate (BodyTemplate1/2, ListTemplate1, WeatherTemplate...). */
    data class Card(val template: JsonObject) : ShowScreen

    /** TemplateRuntime.RenderPlayerInfo (música em reprodução). */
    data class Player(val info: JsonObject) : ShowScreen

    /** Alexa.Presentation.APL.RenderDocument. */
    data class Apl(val token: String, val document: JsonObject, val datasources: JsonObject?) : ShowScreen
}

/**
 * Estado do modo Show. Recebe as diretivas visuais do [com.androidshow.alexa.AlexaService],
 * decide a tela atual, volta para a home após o tempo limite e descreve a janela no contexto.
 */
class ShowModeController(private val scope: CoroutineScope) {
    private val _screen = MutableStateFlow<ShowScreen>(ShowScreen.Home())
    val screen: StateFlow<ShowScreen> = _screen.asStateFlow()

    private val _visible = MutableStateFlow(false)
    /** True enquanto a tela do modo Show está aberta: só então o contexto anuncia uma janela. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private var home: JsonObject? = null
    private var timeout: Job? = null

    fun setVisible(visible: Boolean) {
        _visible.value = visible
    }

    /** Handler de diretivas visuais. Devolve false para o que não é do modo Show. */
    fun handle(d: Directive): Boolean {
        when (d.namespace) {
            "TemplateRuntime" -> when (d.name) {
                "RenderTemplate" -> show(ShowScreen.Card(d.payload), CARD_TIMEOUT_MS)
                "RenderPlayerInfo" -> show(ShowScreen.Player(d.payload), null)
                else -> return false
            }
            "Alexa.Presentation.APL" -> when (d.name) {
                "RenderDocument" -> {
                    val doc = d.payload["document"] as? JsonObject ?: return true
                    val token = d.payload.str("presentationToken") ?: d.payload.str("token").orEmpty()
                    val timeoutMs = when (d.payload.str("timeoutType")) {
                        "TRANSIENT" -> 10_000L
                        "LONG" -> null
                        else -> 30_000L
                    }
                    show(ShowScreen.Apl(token, doc, d.payload["datasources"] as? JsonObject), timeoutMs)
                }
                "ExecuteCommands" -> Log.i(TAG, "APL ExecuteCommands ainda não suportado: ${d.payload}")
                else -> return false
            }
            // [Inferido] Home multimodal do Echo Show (MMHomeAgent no app Windows). Formato a confirmar.
            "Alexa.Presentation.Home" -> {
                Log.i(TAG, "Home ${d.name}: ${d.payload.toString().take(2000)}")
                home = d.payload
                if (_screen.value is ShowScreen.Home) _screen.value = ShowScreen.Home(home)
            }
            "Alexa.Display.Window", "Alexa.Display" -> Log.i(TAG, "${d.key}: ${d.payload}")
            else -> return false
        }
        return true
    }

    /** Toque do usuário fecha o conteúdo atual e volta à home. */
    fun dismiss() {
        timeout?.cancel()
        _screen.value = ShowScreen.Home(home)
    }

    /** Itens de contexto visuais: a janela disponível e o documento APL na tela. */
    fun contextItems(): List<JsonObject> {
        if (!_visible.value) return emptyList()
        val current = _screen.value
        val aplToken = (current as? ShowScreen.Apl)?.token.orEmpty()
        val items = mutableListOf(
            avsState("Alexa.Display.Window", "WindowState", buildJsonObject {
                put("defaultWindowId", WINDOW_ID)
                putJsonArray("instances") {
                    addJsonObject {
                        put("id", WINDOW_ID)
                        put("templateId", WINDOW_TEMPLATE)
                        put("token", aplToken)
                        putJsonObject("configuration") {
                            put("interactionMode", "mobile_mode")
                            put("sizeConfigurationId", "fullscreen")
                        }
                    }
                }
            })
        )
        if (current is ShowScreen.Apl) {
            items += avsState("Alexa.Presentation.APL", "RenderedDocumentState", buildJsonObject {
                put("token", current.token)
                put("version", "AndroidShow-1.0")
                put("componentsVisibleOnScreen", JsonArray(emptyList()))
            })
        }
        return items
    }

    private fun show(screen: ShowScreen, timeoutMs: Long?) {
        timeout?.cancel()
        _screen.value = screen
        if (timeoutMs != null) {
            timeout = scope.launch {
                delay(timeoutMs)
                _screen.value = ShowScreen.Home(home)
            }
        }
    }

    private companion object {
        const val TAG = "ShowMode"
        const val WINDOW_ID = "app_window"
        const val WINDOW_TEMPLATE = "fullscreen_mobile"
        const val CARD_TIMEOUT_MS = 20_000L
    }
}
