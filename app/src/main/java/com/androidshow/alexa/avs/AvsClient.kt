package com.androidshow.alexa.avs

import android.os.SystemClock
import android.util.Log
import com.androidshow.alexa.diag.DiagLog
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
                var openedAt = 0L
                try {
                    _state.value = State.Connecting
                    openDownchannel {
                        openedAt = SystemClock.elapsedRealtime()
                        _state.value = State.Connected
                        launch { runCatching { onConnected() }.onFailure { DiagLog.w("avs", "onConnected", it) } }
                    }
                    DiagLog.i("avs", "downchannel encerrado pelo servidor")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagLog.w("avs", "downchannel caiu: ${e.message}")
                }
                _state.value = State.Disconnected
                // Só zera o backoff se a conexão durou: evita laço apertado quando o servidor fecha na hora.
                if (openedAt > 0 && SystemClock.elapsedRealtime() - openedAt > STABLE_CONNECTION_MS) backoff = MIN_BACKOFF_MS
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
            logResponse("directives", response)
            checkAuthorized(response)
            onOpen()
            if (!readParts(call, response, tap = "downchannel")) throw IOException("downchannel sem multipart")
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
            logResponse(event.toString().substringAfter("\"name\":\"").substringBefore('"'), response)
            checkAuthorized(response)
            if (response.code == 204) return
            readParts(call, response, tap = "evento")
        }
    }

    private fun logResponse(label: String, r: Response) {
        DiagLog.i("avs", "$label → ${r.code} ${r.protocol} ${r.header("Content-Type").orEmpty()} host=${r.request.url.host}")
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
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty().take(2000)
            DiagLog.w("avs", "erro ${response.code}: $body")
            throw HttpException(response.code, body.take(300))
        }
    }

    /**
     * Lê as partes multipart à medida que chegam. Cancelar a coroutine cancela a chamada HTTP.
     * Devolve false se a resposta não era multipart (o início do corpo vai para o diagnóstico).
     *
     * O boundary é lido do cabeçalho cru: o AVS manda `type=application/json` como parâmetro, que o
     * parser de MediaType do OkHttp rejeita (`contentType()` vira null) [Observado no Redmi].
     */
    private suspend fun readParts(call: Call, response: Response, tap: String? = null): Boolean = coroutineScope {
        val body = response.body ?: return@coroutineScope false
        val contentType = response.header("Content-Type").orEmpty()
        val boundary = boundaryOf(contentType)
        if (boundary == null) {
            val text = withContext(Dispatchers.IO) {
                runCatching { body.source().run { request(2000); buffer.snapshot().utf8() } }.getOrDefault("")
            }
            DiagLog.w("avs", "resposta não multipart ($contentType): ${text.take(2000)}")
            return@coroutineScope false
        }
        val canceller = launch {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                MultipartReader(body.source(), boundary).use { reader ->
                    while (true) {
                        val part = reader.nextPart() ?: break
                        val bytes = part.body.use { it.readByteArray() }
                        val contentId = part.headers["Content-ID"]
                        val type = part.headers["Content-Type"].orEmpty()
                        if (contentId != null && !type.contains("json")) {
                            attachments.put(contentId, bytes)
                            DiagLog.i("avs", "anexo $contentId (${bytes.size} bytes) via ${tap ?: "-"}")
                        } else {
                            val directive = Directive.parse(bytes.decodeToString())
                            if (directive != null) {
                                DiagLog.i("avs", "diretiva ${directive.key} via ${tap ?: "-"}")
                                _directives.emit(directive)
                            } else {
                                DiagLog.w("avs", "parte ignorada ($type): ${bytes.decodeToString().take(500)}")
                            }
                        }
                    }
                }
            }
        } finally {
            canceller.cancel()
        }
        true
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
        const val STABLE_CONNECTION_MS = 30_000L
    }
}

/** Extrai o boundary de um Content-Type multipart, tolerando parâmetros fora do padrão. */
internal fun boundaryOf(contentType: String): String? {
    if (!contentType.trimStart().startsWith("multipart/", ignoreCase = true)) return null
    return Regex("""boundary\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
        .find(contentType)?.groupValues?.get(1)?.trim()
}
