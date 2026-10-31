package com.androidshow.alexa.avs

import android.util.Log
import com.androidshow.alexa.auth.AuthRepository
import com.androidshow.alexa.auth.AvsRegion
import com.androidshow.alexa.net.Http
import com.androidshow.alexa.net.HttpException
import com.androidshow.alexa.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Headers.Companion.headersOf
import okhttp3.MultipartBody
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Conexão com o Alexa Voice Service (API v20160207) sobre uma única conexão HTTP/2:
 * downchannel de diretivas, eventos multipart e ping de manutenção.
 */
class AvsClient(
    http: OkHttpClient,
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
    private val attachments: Attachments,
) {
    enum class State { Disconnected, Connecting, Connected }

    /** Mesmo pool de conexões do cliente base, sem timeout de leitura (streams longos). */
    private val http = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _directives = MutableSharedFlow<Directive>(extraBufferCapacity = 64)
    val directives: SharedFlow<Directive> = _directives.asSharedFlow()

    /** Estado do cliente enviado em todo evento (SpeechState, VolumeState etc.). */
    var context: () -> JsonArray = { JsonArray(emptyList()) }

    /** Executado a cada (re)conexão do downchannel, ex.: SynchronizeState. */
    var onConnected: suspend () -> Unit = {}

    @Volatile private var host: String = AvsRegion.NA.gateway
    private var job: Job? = null

    private val base get() = "https://$host"

    fun start(region: AvsRegion) {
        if (job?.isActive == true) return
        host = region.gateway
        job = scope.launch { connectLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = State.Disconnected
    }

    /** System.SetEndpoint: a nuvem pode mandar o cliente para outro host. Reconecta nele. */
    fun setEndpoint(url: String) {
        val newHost = url.removePrefix("https://").substringBefore('/')
        if (newHost.isBlank() || newHost == host) return
        host = newHost
        job?.cancel()
        job = scope.launch { connectLoop() }
    }

    private suspend fun connectLoop() = coroutineScope {
        var backoff = MIN_BACKOFF_MS
        val pinger = launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                if (_state.value == State.Connected) runCatching { ping() }
            }
        }
        try {
            while (true) {
                try {
                    _state.value = State.Connecting
                    openDownchannel {
                        _state.value = State.Connected
                        backoff = MIN_BACKOFF_MS
                        launch { runCatching { onConnected() }.onFailure { Log.w(TAG, "onConnected", it) } }
                    }
                    Log.i(TAG, "Downchannel encerrado pelo servidor")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Downchannel caiu: ${e.message}")
                }
                _state.value = State.Disconnected
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        } finally {
            pinger.cancel()
            _state.value = State.Disconnected
        }
    }

    private suspend fun openDownchannel(onOpen: () -> Unit) {
        val request = Request.Builder()
            .url("$base/v20160207/directives")
            .header("Authorization", "Bearer ${auth.accessToken()}")
            .build()
        val call = http.newCall(request)
        call.await().use { response ->
            checkAuthorized(response)
            onOpen()
            readParts(call, response)
        }
    }

    /**
     * Envia um evento. Com [audio], o corpo é duplex: o áudio continua subindo enquanto as
     * diretivas da resposta (por exemplo, StopCapture) já estão sendo lidas.
     */
    suspend fun sendEvent(event: JsonObject, audio: RequestBody? = null) {
        val metadata = buildJsonObject {
            put("context", context())
            put("event", event)
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(headersOf("Content-Disposition", "form-data; name=\"metadata\""), metadata.toString().toRequestBody(Http.JSON))
            .apply { audio?.let { addPart(headersOf("Content-Disposition", "form-data; name=\"audio\""), it) } }
            .build()
        val body = if (audio != null) DuplexBody(multipart, scope) else multipart
        val request = Request.Builder()
            .url("$base/v20160207/events")
            .header("Authorization", "Bearer ${auth.accessToken()}")
            .post(body)
            .build()
        val call = http.newCall(request)
        call.await().use { response ->
            checkAuthorized(response)
            if (response.code == 204) return
            readParts(call, response)
        }
    }

    suspend fun ping() {
        val request = Request.Builder()
            .url("$base/ping")
            .header("Authorization", "Bearer ${auth.accessToken()}")
            .build()
        http.newCall(request).await().use { checkAuthorized(it) }
    }

    private suspend fun checkAuthorized(response: Response) {
        if (response.code == 401 || response.code == 403) {
            runCatching { auth.accessToken(force = true) }
            throw HttpException(response.code, "token recusado pelo AVS")
        }
        if (!response.isSuccessful) throw HttpException(response.code, response.body?.string().orEmpty().take(300))
    }

    /** Lê as partes multipart à medida que chegam. Cancelar a coroutine cancela a chamada HTTP. */
    private suspend fun readParts(call: Call, response: Response) = coroutineScope {
        val body = response.body ?: return@coroutineScope
        if (body.contentType()?.type != "multipart") return@coroutineScope
        val canceller = launch {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                MultipartReader(body).use { reader ->
                    while (true) {
                        val part = reader.nextPart() ?: break
                        val bytes = part.body.use { it.readByteArray() }
                        val contentId = part.headers["Content-ID"]
                        val type = part.headers["Content-Type"].orEmpty()
                        if (contentId != null && !type.contains("json")) {
                            attachments.put(contentId, bytes)
                        } else {
                            val directive = Directive.parse(bytes.decodeToString())
                            if (directive != null) _directives.emit(directive)
                            else Log.d(TAG, "Parte ignorada: ${bytes.decodeToString().take(200)}")
                        }
                    }
                }
            }
        } finally {
            canceller.cancel()
        }
    }

    /** Envolve um corpo para envio duplex (HTTP/2): grava em segundo plano e devolve na hora. */
    private class DuplexBody(private val delegate: RequestBody, private val scope: CoroutineScope) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun isDuplex() = true
        override fun writeTo(sink: BufferedSink) {
            scope.launch(Dispatchers.IO) {
                try {
                    delegate.writeTo(sink)
                } catch (e: IOException) {
                    Log.d(TAG, "Envio do áudio interrompido: ${e.message}")
                } finally {
                    runCatching { sink.close() }
                }
            }
        }
    }

    private companion object {
        const val TAG = "AvsClient"
        const val PING_INTERVAL_MS = 5 * 60_000L
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
