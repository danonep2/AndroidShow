package com.androidshow.alexa

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.androidshow.alexa.auth.AuthRepository
import com.androidshow.alexa.avs.Attachments
import com.androidshow.alexa.avs.AvsClient
import com.androidshow.alexa.avs.Capabilities
import com.androidshow.alexa.avs.Directive
import com.androidshow.alexa.avs.avsEvent
import com.androidshow.alexa.avs.avsState
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.net.long
import com.androidshow.alexa.net.obj
import com.androidshow.alexa.net.str
import com.androidshow.alexa.voice.AudioCapture
import com.androidshow.alexa.voice.Microphone
import com.androidshow.alexa.voice.MusicPlayer
import com.androidshow.alexa.voice.wake.WakeWordConfig
import com.androidshow.alexa.voice.SpeechPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * Orquestra a sessão Alexa: mantém o AVS conectado enquanto houver login, processa diretivas
 * em ordem e expõe o estado da conversa para a UI.
 */
class AlexaService(
    context: Context,
    private val http: OkHttpClient,
    private val microphone: Microphone,
    private val auth: AuthRepository,
    private val avs: AvsClient,
    private val attachments: Attachments,
    private val scope: CoroutineScope,
) {
    enum class Dialog { Idle, Listening, Thinking, Speaking }

    /** Diretivas de tela (TemplateRuntime, APL, Home); devolve true se tratou. Ligado na etapa do modo Show. */
    var displayHandler: ((Directive) -> Boolean)? = null

    /** Itens extras de contexto (ex.: estado da janela do modo Show). */
    val contextContributors = mutableListOf<() -> List<JsonObject>>()

    private val audio = context.getSystemService(AudioManager::class.java)
    private val debugAudio = if (BuildConfig.DEBUG) java.io.File(context.cacheDir, "last_capture.pcm") else null
    private val player = SpeechPlayer()
    private val music = MusicPlayer(context, attachments, scope) { avs.sendEvent(it) }

    /** Estado da música (AudioPlayer), para a UI. */
    val musicState get() = music.state

    private val _utterance = MutableStateFlow<String?>(null)
    /** Frase reconhecida pela nuvem (vem no RequestProcessingStarted deste tipo de dispositivo). */
    val utterance: StateFlow<String?> = _utterance.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    /** Volume da voz do usuário enquanto a Alexa escuta (0..1), para a onda da tela inicial. */
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()
    private val levelListener = Microphone.Listener { _micLevel.value = Microphone.level(it) }

    /** Intensidade da fala da Alexa (0..1), para o círculo da tela inicial. */
    val speechLevel get() = player.level

    private val _dialog = MutableStateFlow(Dialog.Idle)
    val dialog: StateFlow<Dialog> = _dialog.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    val connection get() = avs.state

    @Volatile private var capture: AudioCapture? = null
    private var speechToken = ""
    private var speechActivity = "FINISHED"
    private var speechOffsetMs = 0L
    /** Reprodução da fala atual; cancelar interrompe (barge-in). */
    @Volatile private var speechJob: Job? = null
    /** Diálogo do último Recognize enviado: a resposta em andamento pertence a ele. */
    @Volatile private var lastDialogRequestId: String? = null
    /** Diálogos interrompidos pelo usuário: diretivas que ainda chegarem deles são descartadas. */
    private val cancelledDialogs = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private var started = false
    private var capabilitiesPublished = false
    private val display = context.resources.displayMetrics

    fun start() {
        if (started) return
        started = true
        avs.context = ::buildContext
        avs.onConnected = {
            publishCapabilities()
            avs.sendEvent(avsEvent("System", "SynchronizeState"))
            sendLocale()
        }

        scope.launch {
            auth.session.collect { s -> if (s != null) avs.start(s.marketplace.region) else avs.stop() }
        }
        // Música abaixa enquanto a Alexa ouve ou fala.
        scope.launch { dialog.collect { music.duck(it != Dialog.Idle) } }
        // StopCapture precisa agir na hora, mesmo com outra diretiva em andamento.
        scope.launch {
            avs.directives.collect { d -> if (d.key == "SpeechRecognizer.StopCapture") stopListening() }
        }
        // As demais são processadas em ordem: a fala termina antes do ExpectSpeech abrir o microfone.
        scope.launch {
            avs.directives.collect { d ->
                if (d.key == "SpeechRecognizer.StopCapture") return@collect
                runCatching { handle(d) }.onFailure { Log.w(TAG, "Falha em ${d.key}", it) }
            }
        }
    }

    /** Envia um evento com o contexto atual (usado pelo modo Show). */
    suspend fun sendEvent(event: JsonObject) = avs.sendEvent(event)

    /** Publica as interfaces suportadas (uma vez por processo); 204 = aceitas. */
    private suspend fun publishCapabilities() {
        if (capabilitiesPublished) return
        val s = auth.session.value ?: return
        val caps = Capabilities.list(maxOf(display.widthPixels, display.heightPixels), minOf(display.widthPixels, display.heightPixels))
        val status = runCatching { Capabilities.publish(http, auth, s, caps) }
            .onFailure { DiagLog.w("caps", "PUT capabilities falhou", it) }.getOrNull()
        val report = runCatching {
            avs.sendEvent(Capabilities.discoveryEvent(s, caps, auth.accessToken()), withContext = false)
        }.onFailure { DiagLog.w("caps", "AddOrUpdateReport falhou", it) }
        capabilitiesPublished = status == 204 && report.isSuccess
    }

    /** Idioma do reconhecimento: um dispositivo recém-registrado não tem locale definido. */
    private suspend fun sendLocale() {
        val locale = auth.session.value?.marketplace?.locale ?: return
        val payload = buildJsonObject {
            put("settings", JsonArray(listOf(buildJsonObject {
                put("key", "locale")
                put("value", locale)
            })))
        }
        runCatching { avs.sendEvent(avsEvent("Settings", "SettingsUpdated", payload)) }
            .onFailure { DiagLog.w("avs", "SettingsUpdated falhou", it) }
    }

    /**
     * Palavra de ativação detectada. Com verificação na nuvem, envia os 2 s anteriores (que contêm a
     * palavra) e marca onde ela está; senão, age como um toque e envia só o que vem depois.
     *
     * O fim da fala fica só com o StopCapture da nuvem: o detector local calibraria o ruído com o
     * usuário ainda falando e cortaria cedo [Observado: corte em 1,2 s].
     */
    fun onWakeWord(preroll: List<ShortArray>, config: WakeWordConfig) {
        if (!config.cloudVerified) {
            startListening(localEndpointing = false)
            return
        }
        val end = preroll.size * Microphone.FRAME_SAMPLES
        val initiator = buildJsonObject {
            put("type", "WAKEWORD")
            put("payload", buildJsonObject {
                put("wakeWord", config.label.uppercase())
                put("wakeWordIndices", buildJsonObject {
                    // O openWakeWord não dá a posição exata: estima ~1 s de palavra antes da detecção.
                    put("startIndexInSamples", (end - WAKE_WORD_SAMPLES).coerceAtLeast(0))
                    put("endIndexInSamples", end)
                })
            })
        }
        startListening(initiator, preroll, localEndpointing = false)
    }

    /** Toque no microfone: abre um Recognize e transmite o áudio até o StopCapture. */
    fun startListening(
        initiator: JsonObject? = null,
        preroll: List<ShortArray> = emptyList(),
        localEndpointing: Boolean = true,
    ): Job? {
        if (capture != null) return null
        // Chamada nova (voz ou toque) com a Alexa falando: corta a resposta atual.
        if (_dialog.value == Dialog.Speaking || speechJob != null) interrupt()
        val dialogRequestId = UUID.randomUUID().toString()
        lastDialogRequestId = dialogRequestId
        val mic = AudioCapture(microphone, preroll, localEndpointing = localEndpointing, debugCopy = debugAudio)
        capture = mic
        microphone.add(levelListener)
        _utterance.value = null
        _dialog.value = Dialog.Listening
        val payload = buildJsonObject {
            put("profile", "NEAR_FIELD")
            put("format", AudioCapture.FORMAT)
            put("initiator", initiator ?: buildJsonObject { put("type", "TAP") })
        }
        return scope.launch {
            try {
                avs.sendEvent(avsEvent("SpeechRecognizer", "Recognize", payload, dialogRequestId), mic)
            } catch (e: Exception) {
                append("Erro no Recognize: ${e.message}")
            } finally {
                mic.stop()
                if (capture === mic) {
                    capture = null
                    stopLevel()
                }
                _dialog.update { if (it == Dialog.Listening || it == Dialog.Thinking) Dialog.Idle else it }
            }
        }
    }

    fun stopListening() {
        capture?.let {
            it.stop()
            capture = null
            stopLevel()
            _dialog.value = Dialog.Thinking
        }
    }

    /**
     * Barge-in: para a fala atual e descarta o resto da resposta (outras falas, ExpectSpeech)
     * do mesmo diálogo. A música não é afetada aqui; "Alexa, para" chega como diretiva própria.
     */
    private fun interrupt() {
        lastDialogRequestId?.let(cancelledDialogs::add)
        speechJob?.let {
            DiagLog.i("fala", "interrompida pelo usuário")
            it.cancel()
        }
    }

    private fun stopLevel() {
        microphone.remove(levelListener)
        _micLevel.value = 0f
    }

    private suspend fun handle(d: Directive) {
        if (d.dialogRequestId != null && d.dialogRequestId in cancelledDialogs) {
            DiagLog.i("avs", "descartada (diálogo interrompido): ${d.key}")
            if (d.key == "SpeechSynthesizer.Speak") d.payload.str("url")?.let(attachments::discard)
            return
        }
        append(d.key)
        when (d.key) {
            "SpeechSynthesizer.Speak" -> speak(d)
            "SpeechRecognizer.ExpectSpeech" -> startListening(d.payload.obj("initiator"))
            "Speaker.SetVolume" -> setVolume(d.payload.long("volume")?.toInt() ?: return)
            "Speaker.AdjustVolume" -> setVolume(currentVolume() + (d.payload.long("volume")?.toInt() ?: 0))
            "Speaker.SetMute" -> setMute(d.payload["mute"]?.toString() == "true")
            "System.SetEndpoint" -> d.payload.str("endpoint")?.let(avs::setEndpoint)
            "System.ResetUserInactivity", "Alexa.EventProcessed",
            "SpeechRecognizer.SetEndOfSpeechOffset", "SpeechRecognizer.RequestProcessingCompleted" -> Unit
            "SpeechRecognizer.RequestProcessingStarted" -> d.payload.str("utterance")?.let {
                _utterance.value = it
                append("Você: $it")
                DiagLog.i("avs", "entendido: \"$it\" (${d.payload.str("intentName")})")
            }
            // A nuvem pede o estado atual; o contexto vai junto em todo evento.
            "System.ReportState" -> avs.sendEvent(avsEvent("System", "StateReport"))
            else -> if (!music.handle(d) && displayHandler?.invoke(d) != true) DiagLog.i("avs", "sem handler: ${d.key} ${d.payload.toString().take(300)}")
        }
    }

    private suspend fun speak(d: Directive) {
        val token = d.payload.str("token").orEmpty()
        val mp3 = d.payload.str("url")?.let { attachments.take(it) }
        if (mp3 == null) {
            append("Speak sem áudio")
            return
        }
        speechToken = token
        speechActivity = "PLAYING"
        speechOffsetMs = 0
        _dialog.value = Dialog.Speaking
        val tokenPayload = buildJsonObject { put("token", token) }
        scope.launch { runCatching { avs.sendEvent(avsEvent("SpeechSynthesizer", "SpeechStarted", tokenPayload)) } }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val job = scope.launch { player.play(mp3) }
        speechJob = job
        try {
            job.join()
        } finally {
            speechJob = null
            speechOffsetMs = android.os.SystemClock.elapsedRealtime() - startedAt
            // Se o usuário já chamou de novo, o estado é Listening: não sobrescreve.
            _dialog.update { if (it == Dialog.Speaking) Dialog.Idle else it }
        }
        if (job.isCancelled) {
            speechActivity = "INTERRUPTED"
            val payload = buildJsonObject {
                put("token", token)
                put("offsetInMilliseconds", speechOffsetMs)
                put("playerActivity", "INTERRUPTED")
            }
            scope.launch { runCatching { avs.sendEvent(avsEvent("SpeechSynthesizer", "SpeechInterrupted", payload)) } }
        } else {
            speechActivity = "FINISHED"
            scope.launch { runCatching { avs.sendEvent(avsEvent("SpeechSynthesizer", "SpeechFinished", tokenPayload)) } }
        }
    }

    private fun currentVolume(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private suspend fun setVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, percent.coerceIn(0, 100) * max / 100, 0)
        avs.sendEvent(avsEvent("Speaker", "VolumeChanged", volumeState()))
    }

    private suspend fun setMute(mute: Boolean) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0,
        )
        avs.sendEvent(avsEvent("Speaker", "MuteChanged", volumeState()))
    }

    private fun volumeState() = buildJsonObject {
        put("volume", currentVolume())
        put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    private fun buildContext(): JsonArray {
        val items = mutableListOf(
            avsState("SpeechSynthesizer", "SpeechState", buildJsonObject {
                put("token", speechToken)
                put("offsetInMilliseconds", if (speechActivity == "PLAYING") 0 else speechOffsetMs)
                put("playerActivity", speechActivity)
            }),
            avsState("Speaker", "VolumeState", volumeState()),
            music.contextItem(),
            avsState("Alerts", "AlertsState", buildJsonObject {
                put("allAlerts", JsonArray(emptyList()))
                put("activeAlerts", JsonArray(emptyList()))
            }),
            avsState("SpeechRecognizer", "RecognizerState", buildJsonObject { put("wakeword", "ALEXA") }),
        )
        contextContributors.forEach { items += it() }
        return JsonArray(items)
    }

    private fun append(line: String) {
        _log.update { (it + line).takeLast(LOG_LINES) }
    }

    private companion object {
        const val TAG = "AlexaService"
        const val LOG_LINES = 50
        const val WAKE_WORD_SAMPLES = Microphone.SAMPLE_RATE // ~1 s
    }
}
