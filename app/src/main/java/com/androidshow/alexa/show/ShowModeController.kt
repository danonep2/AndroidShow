package com.androidshow.alexa.show

import android.util.Log
import com.androidshow.alexa.avs.Capabilities
import com.androidshow.alexa.avs.Directive
import com.androidshow.alexa.avs.avsEvent
import com.androidshow.alexa.avs.avsState
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.net.Http
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
import com.androidshow.alexa.net.obj
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** O que a tela do modo Show está mostrando. */
sealed interface ShowScreen {
    /** Home do Echo Show: relógio e os cartões recebidos por DEV.Alexa.Presentation.Home.UpdateData. */
    data class Home(
        val cards: List<JsonObject> = emptyList(),
        /** Documento APL da home (SPA) e o parâmetro "payload" montado com os dados, para o renderizador. */
        val document: String? = null,
        val payload: String? = null,
        /** Muda a cada "ir para a home" estando já nela: recria o documento na primeira página. */
        val generation: Int = 0,
        /** false: fica no primeiro cartão, sem girar (Ajustes → Modo Show → Ativar Carrossel). */
        val carousel: Boolean = true,
        /** Relógio do app como primeira página (categoria [ShowCategory.APP_CLOCK]). */
        val clock: Boolean = true,
        /** Clima atual para a tela estática: data.data do cartão de previsão, se já chegou. */
        val weather: JsonObject? = null,
    ) : ShowScreen

    /** Cartão TemplateRuntime.RenderTemplate (BodyTemplate1/2, ListTemplate1, WeatherTemplate...). */
    data class Card(val template: JsonObject) : ShowScreen

    /** TemplateRuntime.RenderPlayerInfo (música em reprodução). */
    data class Player(val info: JsonObject) : ShowScreen

    /** Alexa.Presentation.APL.RenderDocument. */
    data class Apl(val token: String, val document: JsonObject, val datasources: JsonObject?) : ShowScreen {
        /**
         * Valor de um parâmetro do mainTemplate: o datasource de mesmo nome ou, por convenção do
         * AVS, todos os datasources quando o parâmetro é "payload".
         */
        fun dataFor(name: String): String? =
            datasources?.get(name)?.toString() ?: if (name == "payload") datasources?.toString() else null

        /** Receitas desenhadas pelo app (null: o renderizador APL desenha o documento). */
        val recipes: com.androidshow.alexa.show.recipes.RecipeView? by lazy {
            runCatching { com.androidshow.alexa.show.recipes.Recipes.parse(this) }
                .onFailure { DiagLog.w("show", "receitas: leitura falhou", it) }.getOrNull()
        }
    }
}

/**
 * Estado do modo Show. Recebe as diretivas visuais do [com.androidshow.alexa.AlexaService],
 * decide a tela atual, volta para a home após o tempo limite e descreve a janela no contexto.
 */
class ShowModeController(private val scope: CoroutineScope, private val settings: ShowSettings) {
    private val _screen = MutableStateFlow<ShowScreen>(ShowScreen.Home())
    val screen: StateFlow<ShowScreen> = _screen.asStateFlow()

    init {
        // Mudou nos Ajustes: redesenha a home já com ou sem carrossel.
        scope.launch {
            settings.config.collect { if (_screen.value is ShowScreen.Home) _screen.value = homeScreen() }
        }
    }

    private val _visible = MutableStateFlow(false)
    /** True enquanto a tela do modo Show está aberta: só então o contexto anuncia uma janela. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private var timeout: Job? = null

    /** Envia eventos ao AVS (ligado pelo AppGraph). */
    var sendEvent: (suspend (JsonObject) -> Unit)? = null

    /** Toque do usuário numa tela da Alexa: corta a fala atual, como o app Windows faz. */
    var onUserTouch: (() -> Unit)? = null

    private val _recipeCommands = kotlinx.coroutines.flow.MutableSharedFlow<com.androidshow.alexa.show.recipes.RecipeCommand>(extraBufferCapacity = 8)
    /** "Próximo passo", "mostrar ingredientes"...: ExecuteCommands traduzidos para a tela nativa. */
    val recipeCommands: kotlinx.coroutines.flow.SharedFlow<com.androidshow.alexa.show.recipes.RecipeCommand> = _recipeCommands

    /** Os dumps de APL são numerados para guardar a sequência (lista → detalhe → passo a passo). */
    private var aplDumpSeq = 0

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
    private suspend fun sendHomeEvent(name: String, payload: JsonObject = JsonObject(emptyMap())) {
        val send = sendEvent ?: return
        for (ns in listOf(homeNamespace) + HOME_NAMESPACES.filter { it != homeNamespace }) {
            val ok = runCatching { send(avsEvent(ns, name, payload)) }
                .onFailure { DiagLog.w("show", "$ns.$name recusado: ${it.message}") }
                .isSuccess
            if (ok) {
                homeNamespace = ns
                DiagLog.i("show", "$ns.$name enviado")
                return
            }
        }
    }

    /** dataIds referenciados pelo documento da home (datasources.items[].dataId), na ordem de exibição. */
    private fun homeDataIds(): List<String> =
        (homeDocument?.obj("datasources")?.get("items") as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("dataId") }
            .distinct()

    /** Pede os dados dos cartões: o DataExpired exige a lista de dataIds [Observado: 400 sem ela]. */
    private suspend fun requestHomeData() {
        val ids = homeDataIds()
        if (ids.isEmpty()) return
        DiagLog.i("show", "pedindo dados de ${ids.size} cartões")
        sendHomeEvent("DataExpired", buildJsonObject {
            put("dataIds", kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })
    }

    /** Nomes das opções da home vindos do UpdateSettingSchemas, por id da opção. */
    private val homeSchemas = mutableMapOf<String, ShowCategory>()

    /** Registra nos Ajustes as categorias dos cartões recebidos, com o nome dado pela Amazon. */
    private fun rememberCategories() {
        val seen = homeItems.values.mapNotNull(::categoryOf).distinct()
        settings.update { c ->
            val known = c.categories.filter { it != ShowCategory.APP_CLOCK }.associateBy { it.id }.toMutableMap()
            for (id in seen) {
                val schema = SCHEMA_FOR_CATEGORY[id]?.let(homeSchemas::get)
                known[id] = ShowCategory(id, schema?.label ?: known[id]?.label ?: id, schema?.description ?: known[id]?.description)
            }
            c.copy(categories = listOf(ShowCategory.APP_CLOCK) + known.values.sortedBy { it.label })
        }
    }

    private fun homeScreen(): ShowScreen.Home {
        val hidden = settings.config.value.hidden
        val items = homeItems.filterValues { categoryOf(it) !in hidden }
        val order = homeDataIds()
        val sorted = if (order.isEmpty()) items.values.toList()
        else order.mapNotNull { items[it] } + items.filterKeys { it !in order }.values
        val doc = homeDocument
        val refs = (doc?.obj("datasources")?.get("items") as? kotlinx.serialization.json.JsonArray).orEmpty()
        // Como o app Windows (AssembleSPADocumentAndData): cada página recebe o item do UpdateData.
        // Página sem dados fica escondida pelo "when" do documento; é assim que some uma categoria.
        val payload = if (doc != null && items.isNotEmpty()) buildJsonObject {
            put("items", kotlinx.serialization.json.JsonArray(refs.map { ref ->
                val id = (ref as? JsonObject)?.str("dataId")
                id?.let { items[it]?.obj("data") } ?: ref
            }))
        }.toString().replace(WAKE_WORD_PLACEHOLDER, "Alexa") else null
        val carousel = settings.config.value.carousel
        val clock = ShowCategory.APP_CLOCK.id !in hidden
        val document = doc?.obj("document")
            ?.let { if (clock) withClockPage(it) else it }
            ?.let { if (carousel) it else withoutPaging(it) }
        val weather = homeItems.values.firstOrNull { categoryOf(it) == WEATHER_CATEGORY }?.obj("data")?.obj("data")
        return ShowScreen.Home(sorted, document?.toString(), payload, homeGeneration, carousel, clock, weather)
    }

    /** Põe o relógio do app como primeira página do pager da home. */
    private fun withClockPage(document: JsonObject): JsonObject {
        val mainTemplate = document.obj("mainTemplate") ?: return document
        val page = Http.json.parseToJsonElement(CLOCK_PAGE)
        return JsonObject(document + ("mainTemplate" to mapPager(mainTemplate) { pager ->
            JsonObject(pager + ("items" to JsonArray(listOf(page) + (pager["items"] as? JsonArray).orEmpty())))
        }))
    }

    /**
     * Home parada na primeira página: o pager gira chamando o comando NextPage do documento
     * (SetPage relativo a cada 10 s) [Observado no documento SPA]; vira Idle e o arrastar é travado.
     */
    private fun withoutPaging(document: JsonObject): JsonObject {
        val commands = document.obj("commands")
        val mainTemplate = document.obj("mainTemplate")
        return JsonObject(document + buildMap {
            if (commands?.containsKey("NextPage") == true) {
                put("commands", JsonObject(commands + ("NextPage" to buildJsonObject {
                    putJsonObject("command") { put("type", "Idle") }
                })))
            }
            if (mainTemplate != null) put("mainTemplate", mapPager(mainTemplate) {
                JsonObject(it + ("navigation" to kotlinx.serialization.json.JsonPrimitive("none")))
            })
        })
    }

    /** Aplica [f] ao pager principal da home (mmHomeMainPager), onde quer que ele esteja. */
    private fun mapPager(template: JsonObject, f: (JsonObject) -> JsonObject): JsonObject {
        fun walk(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
            is JsonObject -> if (e.str("id") == HOME_PAGER_ID) f(e) else JsonObject(e.mapValues { walk(it.value) })
            is JsonArray -> JsonArray(e.map(::walk))
            else -> e
        }
        return walk(template) as JsonObject
    }

    /**
     * SendEvent de um documento APL. Na home, a troca de página é só telemetria; nas skills, vira
     * Alexa.Presentation.APL.UserEvent com os argumentos do comando.
     */
    fun onAplSendEvent(token: String, args: Array<Any?>) {
        val json = runCatching { Http.json.parseToJsonElement(org.json.JSONArray(args).toString()) }.getOrNull()
            as? kotlinx.serialization.json.JsonArray ?: return
        val first = json.firstOrNull() as? JsonObject
        if (first?.get("isMMHomePagerOnPageChanged") != null) return
        sendUserEvent(token, json)
    }

    /**
     * SendEvent da home: a troca de página é só telemetria; o toque num cartão manda o evento
     * pronto que o próprio cartão traz para aquela parte (ver [HomeCardActions]).
     */
    fun onHomeSendEvent(args: Array<Any?>, source: Map<String, Any?>) {
        val json = runCatching { Http.json.parseToJsonElement(org.json.JSONArray(args).toString()) }.getOrNull()
            as? JsonArray ?: return
        if ((json.firstOrNull() as? JsonObject)?.get("isMMHomePagerOnPageChanged") != null) return
        val sourceId = source["id"]?.toString()?.takeIf { it.isNotBlank() }
        val event = HomeCardActions.eventFor(homeItems.values, json, sourceId)
        if (event == null) {
            DiagLog.w("show", "toque na home sem ação no cartão (componente $sourceId): ${json.toString().take(200)}")
            return
        }
        DiagLog.i("show", "toque na home (componente $sourceId): ${event.namespace}.${event.name} ${event.payload.toString().take(300)}")
        onUserTouch?.invoke()
        scope.launch {
            runCatching { sendEvent?.invoke(avsEvent(event.namespace, event.name, event.payload)) }
                .onFailure { DiagLog.w("show", "toque na home falhou", it) }
        }
    }

    /**
     * Toque numa receita da lista nativa: manda o UserEvent que o documento APL mandaria.
     * false quando o documento não traz a ação do item (o detalhe abre só no app).
     */
    fun selectRecipe(screen: ShowScreen.Apl, recipe: com.androidshow.alexa.show.recipes.Recipe): Boolean {
        val args = com.androidshow.alexa.show.recipes.RecipeTouch.argumentsFor(screen.document, screen.datasources, recipe)
        if (args == null) {
            DiagLog.i("show", "receita ${recipe.index + 1}: documento sem SendEvent do item, abrindo detalhe local")
            return false
        }
        sendUserEvent(screen.token, args)
        return true
    }

    /** Botão do documento (ex.: "Iniciar receita") ou o "voltar" dos layouts da cozinha (`["back"]`). */
    fun sendRecipeAction(screen: ShowScreen.Apl, arguments: JsonArray) = sendUserEvent(screen.token, arguments)

    /** Telas de receita já vistas (lista → detalhe → passo a passo → lista avulsa), para o voltar. */
    private val recipeStack = ArrayDeque<ShowScreen.Apl>()

    private fun trackRecipeNavigation(current: ShowScreen.Apl?, next: ShowScreen.Apl) {
        val key = next.recipes?.key
        if (key == null) { recipeStack.clear(); return }
        // A nuvem voltou sozinha a uma tela já vista (ex.: "Alexa, voltar"): descarta o que veio depois.
        val seen = recipeStack.indexOfLast { it.recipes?.key == key }
        if (seen >= 0) {
            while (recipeStack.size > seen) recipeStack.removeLast()
        } else if (current?.recipes != null) {
            recipeStack.addLast(current)
            while (recipeStack.size > 8) recipeStack.removeFirst()
        }
    }

    /**
     * Voltar nas receitas: volta na hora para a tela anterior e avisa a nuvem com `["back"]`
     * (o NavigateBack dos layouts da cozinha), para "próximo passo" e afins seguirem dali.
     * [Observado] O voltar do documento APL da Amazon não chegava a sair do app.
     */
    /** Gesto de voltar do sistema: dentro das receitas, volta uma tela; true se tratou. */
    fun systemBackInRecipes(): Boolean {
        val current = _screen.value as? ShowScreen.Apl ?: return false
        if (current.recipes == null || recipeStack.isEmpty()) return false
        recipeBack(current)
        return true
    }

    fun recipeBack(screen: ShowScreen.Apl) {
        sendUserEvent(screen.token, JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("back"))))
        stopStepAudio()
        val previous = recipeStack.removeLastOrNull()
        if (previous != null) {
            DiagLog.i("show", "receitas: voltando para ${previous.recipes?.key}")
            show(previous, null)
        }
    }

    /** Fotos por nome de receita, vistas na lista ou no detalhe (o passo a passo vem sem foto). */
    private val recipeImages = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 60
    }

    private fun rememberRecipeImages(view: com.androidshow.alexa.show.recipes.RecipeView?) {
        val list = when (view) {
            is com.androidshow.alexa.show.recipes.RecipeView.Results -> view.recipes
            is com.androidshow.alexa.show.recipes.RecipeView.Detail -> listOf(view.recipe)
            else -> emptyList()
        }
        // A foto do detalhe vale mais que a da lista (dois resultados podem ter o mesmo nome).
        list.forEach { r -> r.image?.let { if (view is com.androidshow.alexa.show.recipes.RecipeView.Detail || r.title !in recipeImages) recipeImages[r.title] = it } }
    }

    fun recipeImage(title: String): String? = recipeImages[title]

    private var stepAudio: android.media.MediaPlayer? = null

    /**
     * Passo escolhido na tela do passo a passo. Faz o que o comando SelectStepAndUpdateContext do
     * documento faz [Observado no dump]: lê o passo (SpeakItem do stepSpeech) e avisa a nuvem com
     * `["updateCookAlongOrdinal", ordinal]`, para "próximo passo" continuar dali.
     */
    fun selectRecipeStep(screen: ShowScreen.Apl, recipe: com.androidshow.alexa.show.recipes.Recipe, step: Int) {
        sendUserEvent(screen.token, JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("updateCookAlongOrdinal"), kotlinx.serialization.json.JsonPrimitive(step + 1))))
        stopStepAudio()
        val url = recipe.stepSpeech.getOrNull(step) ?: return
        stepAudio = runCatching {
            android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                setDataSource(url)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stopStepAudio() }
                setOnErrorListener { _, what, extra -> DiagLog.w("show", "áudio do passo falhou: $what/$extra"); stopStepAudio(); true }
                prepareAsync()
            }
        }.onFailure { DiagLog.w("show", "áudio do passo", it) }.getOrNull()
    }

    private fun stopStepAudio() {
        stepAudio?.let { runCatching { it.stop() }; it.release() }
        stepAudio = null
    }

    private fun sendUserEvent(token: String, json: JsonArray) {
        DiagLog.i("show", "APL UserEvent: ${json.toString().take(300)}")
        onUserTouch?.invoke()
        scope.launch {
            runCatching {
                sendEvent?.invoke(avsEvent("Alexa.Presentation.APL", "UserEvent", buildJsonObject {
                    put("presentationToken", token)
                    put("arguments", json)
                    put("source", buildJsonObject { put("type", "TouchWrapper"); put("handler", "Press") })
                    put("components", JsonObject(emptyMap()))
                }))
            }.onFailure { DiagLog.w("show", "UserEvent falhou", it) }
        }
    }

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
            if (d.namespace == "Alexa.Presentation.APL") {
                DiagLog.dump("apl-${(aplDumpSeq++ % APL_DUMPS).toString().padStart(2, '0')}-${d.name}", d.payload.toString())
            }
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
                    // Só o documento com o pager da home: uma resposta (ex.: lista de receitas) que chegue
                    // logo após abrir o modo Show era engolida como home [Observado em 2026-11-07 20:14].
                    val isHomeDocument = android.os.SystemClock.elapsedRealtime() - homeStartedAt < HOME_DOCUMENT_WINDOW_MS &&
                        doc.toString().contains("\"$HOME_PAGER_ID\"")
                    if (isHomeDocument) {
                        homeDocument = d.payload
                        DiagLog.dump("home-document", d.payload.toString())
                        DiagLog.i("show", "documento da home (SPA) recebido")
                        if (homeItems.isEmpty()) scope.launch { requestHomeData() }
                        return true
                    }
                    val token = d.payload.str("presentationToken") ?: d.payload.str("token").orEmpty()
                    val timeoutMs = when (d.payload.str("timeoutType")) {
                        "TRANSIENT" -> 10_000L
                        "LONG" -> null
                        else -> 30_000L
                    }
                    val apl = ShowScreen.Apl(token, doc, d.payload["datasources"] as? JsonObject)
                    val recipes = apl.recipes
                    if (recipes != null) DiagLog.i("show", "receitas nativas: ${recipes::class.simpleName}")
                    rememberRecipeImages(recipes)
                    // A cada "próximo passo" a nuvem reenvia o mesmo documento [Observado]: a tela fica
                    // (sem piscar nem voltar ao passo 1) e só o passo do visualContext é aplicado.
                    val current = _screen.value as? ShowScreen.Apl
                    // "Mostrar ingredientes" no passo a passo: abre o painel nativo por cima do passo atual.
                    val currentCooking = (current?.recipes as? com.androidshow.alexa.show.recipes.RecipeView.Detail)?.takeIf { it.cooking }
                    if (recipes is com.androidshow.alexa.show.recipes.RecipeView.GenericList && recipes.isIngredients && currentCooking != null) {
                        _recipeCommands.tryEmit(com.androidshow.alexa.show.recipes.RecipeCommand.OpenIngredientsList)
                        return true
                    }
                    if (recipes != null && current?.recipes?.key == recipes.key) {
                        (recipes as? com.androidshow.alexa.show.recipes.RecipeView.Detail)?.takeIf { it.cooking }?.let {
                            _recipeCommands.tryEmit(com.androidshow.alexa.show.recipes.RecipeCommand.GoTo(it.step))
                        }
                        return true
                    }
                    trackRecipeNavigation(current, apl)
                    // Receita fica na tela enquanto se cozinha; sai pelo Home ou pelo voltar.
                    show(apl, if (recipes != null) null else timeoutMs)
                }
                "ExecuteCommands" -> {
                    val current = _screen.value as? ShowScreen.Apl
                    val token = d.payload.str("presentationToken") ?: d.payload.str("token")
                    if (current?.recipes != null && (token == null || token == current.token)) {
                        val cmds = com.androidshow.alexa.show.recipes.RecipeCommand.from(d.payload["commands"] ?: JsonArray(emptyList()))
                        DiagLog.i("show", "ExecuteCommands nas receitas: $cmds")
                        cmds.forEach { _recipeCommands.tryEmit(it) }
                    } else {
                        Log.i(TAG, "APL ExecuteCommands ainda não suportado: ${d.payload}")
                    }
                }
                else -> return false
            }
            in HOME_NAMESPACES -> {
                homeNamespace = d.namespace
                when (d.name) {
                    "RenderDocument" -> {
                        homeDocument = d.payload
                        if (homeItems.isEmpty()) scope.launch { requestHomeData() }
                    }
                    "UpdateSettingSchemas" -> {
                        (d.payload["schemas"] as? JsonArray).orEmpty().forEach { s ->
                            val o = s as? JsonObject ?: return@forEach
                            val id = o.str("id") ?: return@forEach
                            homeSchemas[id] = ShowCategory(id, o.str("name") ?: id, o.str("description"))
                        }
                        if (homeItems.isNotEmpty()) rememberCategories()
                    }
                    "UpdateData" -> {
                        collectHomeItems(d.payload)
                        DiagLog.i("show", "home: ${homeItems.size} cartões")
                        rememberCategories()
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
    private var homeGeneration = 0

    /** Botão Home: sai do cartão/resposta atual ou, já na home, volta à primeira página dela. */
    fun goHome() {
        if (_screen.value is ShowScreen.Home) homeGeneration++
        dismiss()
    }

    /** true se a tela atual não é a home (o "voltar" deve primeiro voltar para ela). */
    val isAwayFromHome: Boolean get() = _screen.value !is ShowScreen.Home

    fun dismiss() {
        timeout?.cancel()
        stopStepAudio()
        recipeStack.clear()
        // O app Windows avisa a nuvem ao fechar um documento [Observado: Alexa.Presentation.Dismissed].
        (_screen.value as? ShowScreen.Apl)?.token?.takeIf { it.isNotEmpty() }?.let { token ->
            scope.launch {
                runCatching {
                    sendEvent?.invoke(avsEvent("Alexa.Presentation", "Dismissed", buildJsonObject { put("presentationToken", token) }))
                }.onFailure { DiagLog.w("show", "Dismissed falhou: ${it.message}") }
            }
        }
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
        const val APL_DUMPS = 12
        /** As dicas da home vêm com "{WakeWord}"; o app Windows troca pela palavra antes de desenhar. */
        const val WAKE_WORD_PLACEHOLDER = "{WakeWord}"
        const val WINDOW_ID = "app_window"
        const val HOME_PAGER_ID = "mmHomeMainPager"
        /** Cartão de onde a tela estática tira o clima atual. */
        const val WEATHER_CATEGORY = "HourlyWeatherForecast"

        /**
         * Página do relógio do app: um quadro transparente no pager, com o [Clock] nativo atrás da
         * view APL; as outras páginas têm fundo próprio e o cobrem. Textos no documento não servem:
         * o viewhost 2024.3 desenhou todos com o tamanho e a cor do primeiro [Observado]. Saber a
         * página pelo SendEvent do pager também não: ele nunca chega ao app [Observado].
         */
        const val CLOCK_PAGE = """{"type": "Frame", "width": "100vw", "height": "100vh"}"""

        /**
         * Categoria do cartão → opção do UpdateSettingSchemas que a liga e desliga. [Inferido] O
         * protocolo não liga um ao outro; o par saiu da comparação dos nomes e descrições.
         */
        val SCHEMA_FOR_CATEGORY = mapOf(
            "RecentlyPlayedMusic" to "show_home_recently_played_music",
            "HourlyWeatherForecast" to "current_weather",
            "WeatherAlerts" to "weather_alerts",
            "PopularFoodVideo" to "popular_food_videos",
        )

        /** Categoria de um item do UpdateData (data.injectedMetadata.name). */
        fun categoryOf(item: JsonObject): String? = item.obj("data")?.obj("injectedMetadata")?.str("name")
        const val CARD_TIMEOUT_MS = 20_000L
    }
}
