package com.androidshow.alexa.show

import android.util.Log
import com.androidshow.alexa.avs.Capabilities
import com.androidshow.alexa.avs.Directive
import com.androidshow.alexa.avs.avsEvent
import com.androidshow.alexa.avs.avsState
import com.androidshow.alexa.diag.DiagLog
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
    /** Home do Echo Show: relógio e os cartões recebidos por DEV.Alexa.Presentation.Home.UpdateData. */
    data class Home(val cards: List<JsonObject> = emptyList()) : ShowScreen

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

    private var timeout: Job? = null

    /** Envia eventos ao AVS (ligado pelo AppGraph). */
    var sendEvent: (suspend (JsonObject) -> Unit)? = null

    // Estado da home multimodal (MMHome). Protocolo observado nos logs do app Windows:
    // HomeStarted → RenderDocument (APL "SPA") + UpdateSettingSchemas → DataExpired → UpdateData.
    private val homeItems = LinkedHashMap<String, JsonObject>()
    private var homeDocument: JsonObject? = null
    private var homeNamespace = HOME_NAMESPACES.first()
    private var homeStartedAt = 0L

    fun setVisible(visible: Boolean) {
        DiagLog.i("show", if (visible) "modo Show aberto" else "modo Show fechado")
        _visible.value = visible
        if (visible) {
            homeStartedAt = android.os.SystemClock.elapsedRealtime()
            scope.launch { sendHomeEvent("HomeStarted") }
        }
    }

    /** Envia um evento da home; se o namespace DEV for recusado, tenta o público uma vez. */
    private suspend fun sendHomeEvent(name: String) {
        val send = sendEvent ?: return
        for (ns in listOf(homeNamespace) + HOME_NAMESPACES.filter { it != homeNamespace }) {
            val ok = runCatching { send(avsEvent(ns, name)) }
                .onFailure { DiagLog.w("show", "$ns.$name recusado: ${it.message}") }
                .isSuccess
            if (ok) {
                homeNamespace = ns
                DiagLog.i("show", "$ns.$name enviado")
                return
            }
        }
    }

    private fun homeScreen() = ShowScreen.Home(homeItems.values.toList())

    /** Guarda os itens de UpdateData: qualquer objeto com id "pull://…" (formato do MMHomeDataStore do Windows). */
    private fun collectHomeItems(e: kotlinx.serialization.json.JsonElement) {
        when (e) {
            is JsonObject -> {
                val id = e.str("id")
                if (id != null && id.startsWith("pull://") && e["data"] is JsonObject) homeItems[id] = e
                else e.values.forEach(::collectHomeItems)
            }
            is kotlinx.serialization.json.JsonArray -> e.forEach(::collectHomeItems)
            else -> Unit
        }
    }

    /** Handler de diretivas visuais. Devolve false para o que não é do modo Show. */
    fun handle(d: Directive): Boolean {
        if (d.namespace in VISUAL_NAMESPACES) {
            DiagLog.i("show", "${d.key}: ${d.payload.toString().length} bytes")
            DiagLog.dump(d.key, d.payload.toString())
        }
        when (d.namespace) {
            "TemplateRuntime" -> when (d.name) {
                "RenderTemplate" -> show(ShowScreen.Card(d.payload), CARD_TIMEOUT_MS)
                "RenderPlayerInfo" -> show(ShowScreen.Player(d.payload), null)
                else -> return false
            }
            "Alexa.Presentation.APL" -> when (d.name) {
                "RenderDocument" -> {
                    val doc = d.payload["document"] as? JsonObject ?: return true
                    // [Inferido] O documento SPA da home chega como APL logo após o HomeStarted.
                    if (android.os.SystemClock.elapsedRealtime() - homeStartedAt < HOME_DOCUMENT_WINDOW_MS) {
                        homeDocument = d.payload
                        DiagLog.dump("home-document", d.payload.toString())
                        DiagLog.i("show", "documento da home (SPA) recebido")
                        if (homeItems.isEmpty()) scope.launch { sendHomeEvent("DataExpired") }
                        return true
                    }
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
            in HOME_NAMESPACES -> {
                homeNamespace = d.namespace
                when (d.name) {
                    "RenderDocument" -> {
                        homeDocument = d.payload
                        if (homeItems.isEmpty()) scope.launch { sendHomeEvent("DataExpired") }
                    }
                    "UpdateData" -> {
                        collectHomeItems(d.payload)
                        DiagLog.i("show", "home: ${homeItems.size} cartões")
                        if (_screen.value is ShowScreen.Home) _screen.value = homeScreen()
                    }
                    else -> DiagLog.i("show", "home ${d.name}")
                }
            }
            "Alexa.Display.Window", "Alexa.Display" -> Log.i(TAG, "${d.key}: ${d.payload}")
            else -> return false
        }
        return true
    }

    /** Toque do usuário fecha o conteúdo atual e volta à home. */
    fun dismiss() {
        timeout?.cancel()
        _screen.value = homeScreen()
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
                        put("templateId", Capabilities.WINDOW_TEMPLATE)
                        put("token", aplToken)
                        putJsonObject("configuration") {
                            put("interactionMode", Capabilities.INTERACTION_MODE)
                            put("sizeConfigurationId", Capabilities.WINDOW_SIZE)
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
                _screen.value = homeScreen()
            }
        }
    }

    private companion object {
        const val TAG = "ShowMode"
        /** O app Windows usa "DEV.Alexa.Presentation.Home" [Observado nos logs]; o público fica de reserva. */
        val HOME_NAMESPACES = listOf("DEV.Alexa.Presentation.Home", "Alexa.Presentation.Home")
        val VISUAL_NAMESPACES = setOf("TemplateRuntime", "Alexa.Presentation.APL", "Alexa.Display.Window", "Alexa.Display") + HOME_NAMESPACES
        const val HOME_DOCUMENT_WINDOW_MS = 8_000L
        const val WINDOW_ID = "app_window"
        const val CARD_TIMEOUT_MS = 20_000L
    }
}
