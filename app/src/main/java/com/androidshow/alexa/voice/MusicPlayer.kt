package com.androidshow.alexa.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.androidshow.alexa.avs.Attachments
import com.androidshow.alexa.avs.Directive
import com.androidshow.alexa.avs.avsEvent
import com.androidshow.alexa.avs.avsState
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.net.long
import com.androidshow.alexa.net.obj
import com.androidshow.alexa.net.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Interface AVS AudioPlayer: toca música, rádio e podcasts a partir da URL do stream
 * (HLS `.m3u8` no Amazon Music [Observado]) com o MediaPlayer do sistema, sem bibliotecas extras.
 * Mantém a fila (ENQUEUE), informa o estado no contexto e envia os eventos de reprodução.
 */
class MusicPlayer(
    private val context: Context,
    private val attachments: Attachments,
    private val scope: CoroutineScope,
    private val send: suspend (JsonObject) -> Unit,
) {
    enum class Activity { IDLE, PLAYING, PAUSED, BUFFER_UNDERRUN, FINISHED, STOPPED }

    data class State(val activity: Activity = Activity.IDLE, val token: String = "")

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var current: JsonObject? = null
    private val queue = ArrayDeque<JsonObject>()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Trata AudioPlayer.*; devolve false para outras diretivas. */
    suspend fun handle(d: Directive): Boolean {
        if (d.namespace != "AudioPlayer") return false
        withContext(Dispatchers.Main) {
            when (d.name) {
                "Play" -> play(d.payload)
                "Stop" -> stop()
                "ClearQueue" -> {
                    queue.clear()
                    if (d.payload.str("clearBehavior") == "CLEAR_ALL") stop()
                }
                else -> DiagLog.i("music", "AudioPlayer.${d.name} ignorado")
            }
        }
        return true
    }

    /** Abaixa o volume enquanto a Alexa fala (interruptedBehavior ATTENUATE). */
    fun duck(on: Boolean) = main.post { player?.setVolume(if (on) 0.2f else 1f, if (on) 0.2f else 1f) }

    fun contextItem(): JsonObject = avsState("AudioPlayer", "PlaybackState", buildJsonObject {
        put("token", _state.value.token)
        put("offsetInMilliseconds", offsetMs())
        put("playerActivity", _state.value.activity.name)
    })

    private fun play(payload: JsonObject) {
        val item = payload.obj("audioItem") ?: return
        when (payload.str("playBehavior")) {
            "ENQUEUE" -> {
                if (player == null) start(item) else queue.addLast(item)
            }
            "REPLACE_ENQUEUED" -> {
                queue.clear()
                if (player == null) start(item) else queue.addLast(item)
            }
            else -> { // REPLACE_ALL
                queue.clear()
                stop(sendEvent = player != null)
                start(item)
            }
        }
    }

    private fun start(item: JsonObject) {
        val stream = item.obj("stream") ?: return
        val token = stream.str("token").orEmpty()
        val url = stream.str("url") ?: return
        val offset = stream.long("offsetInMilliseconds")?.toInt() ?: 0
        current = item
        _state.value = State(Activity.BUFFER_UNDERRUN, token)
        DiagLog.i("music", "tocando ${url.substringBefore('?').takeLast(60)} (offset $offset ms)")

        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        mp.setOnPreparedListener {
            if (offset > 0) it.seekTo(offset)
            it.start()
            _state.value = State(Activity.PLAYING, token)
            event("PlaybackStarted", token)
            // Pede o próximo item já no início: a nuvem responde com Play ENQUEUE.
            event("PlaybackNearlyFinished", token)
        }
        mp.setOnCompletionListener {
            event("PlaybackFinished", token)
            release()
            _state.value = State(Activity.FINISHED, token)
            queue.removeFirstOrNull()?.let(::start)
        }
        mp.setOnErrorListener { _, what, extra ->
            DiagLog.w("music", "erro do MediaPlayer what=$what extra=$extra")
            failed(token, "what=$what extra=$extra")
            true
        }
        runCatching {
            if (url.startsWith("cid:")) {
                scope.launch(Dispatchers.Main) {
                    val bytes = attachments.take(url)
                    if (bytes == null || player !== mp) return@launch
                    mp.setDataSource(BytesSource(bytes))
                    mp.prepareAsync()
                }
            } else {
                mp.setDataSource(url)
                mp.prepareAsync()
            }
        }.onFailure {
            DiagLog.w("music", "falha ao abrir stream", it)
            failed(token, it.message.orEmpty())
        }
    }

    private fun stop(sendEvent: Boolean = true) {
        val token = _state.value.token
        if (player != null && sendEvent) event("PlaybackStopped", token)
        release()
        _state.value = State(if (token.isEmpty()) Activity.IDLE else Activity.STOPPED, token)
    }

    private fun failed(token: String, message: String) {
        val offset = offsetMs()
        release()
        _state.value = State(Activity.STOPPED, token)
        scope.launch {
            runCatching {
                send(avsEvent("AudioPlayer", "PlaybackFailed", buildJsonObject {
                    put("token", token)
                    put("currentPlaybackState", buildJsonObject {
                        put("token", token)
                        put("offsetInMilliseconds", offset)
                        put("playerActivity", "STOPPED")
                    })
                    put("error", buildJsonObject {
                        put("type", "MEDIA_ERROR_UNKNOWN")
                        put("message", message)
                    })
                }))
            }
        }
    }

    private fun event(name: String, token: String) {
        val offset = offsetMs()
        DiagLog.i("music", "$name (offset $offset ms)")
        scope.launch {
            runCatching {
                send(avsEvent("AudioPlayer", name, buildJsonObject {
                    put("token", token)
                    put("offsetInMilliseconds", offset)
                }))
            }.onFailure { DiagLog.w("music", "$name falhou", it) }
        }
    }

    private fun offsetMs(): Long = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0L

    private fun release() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        current = null
    }

    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun getSize() = data.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun close() = Unit
    }
}
