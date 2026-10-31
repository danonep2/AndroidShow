package com.androidshow.alexa.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.androidshow.alexa.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Toca o MP3 da fala da Alexa direto da memória (sem arquivo temporário). */
class SpeechPlayer {
    private val _level = MutableStateFlow(0f)
    /** Intensidade da fala tocando agora (0..1), acompanha a posição da reprodução. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Suspende até a fala terminar. Cancelar interrompe a reprodução. */
    suspend fun play(mp3: ByteArray) = coroutineScope {
        // Decodifica em paralelo (bem mais rápido que tempo real) para não atrasar o início da fala.
        val envelope = AtomicReference<FloatArray?>()
        val decode = launch(Dispatchers.Default) {
            runCatching { envelope.set(SpeechEnvelope.of(mp3)) }
                .onFailure { DiagLog.w("fala", "curva de intensidade falhou", it) }
        }
        try {
            playOnMain(mp3, envelope)
        } finally {
            decode.cancel()
            _level.value = 0f
        }
    }

    private suspend fun playOnMain(mp3: ByteArray, envelope: AtomicReference<FloatArray?>) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val player = MediaPlayer()
            var released = false
            val ticker = object : Runnable {
                override fun run() {
                    if (released) return
                    val curve = envelope.get()
                    if (curve != null && player.isPlaying) {
                        _level.value = curve.getOrElse(player.currentPosition / SpeechEnvelope.WINDOW_MS) { 0f }
                    }
                    handler.postDelayed(this, TICK_MS)
                }
            }
            fun release() {
                if (!released) {
                    released = true
                    handler.removeCallbacks(ticker)
                    _level.value = 0f
                    player.release()
                }
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(ByteArraySource(mp3))
            player.setOnPreparedListener {
                it.start()
                handler.post(ticker)
            }
            player.setOnCompletionListener {
                release()
                if (cont.isActive) cont.resume(Unit)
            }
            player.setOnErrorListener { _, _, _ ->
                release()
                if (cont.isActive) cont.resume(Unit)
                true
            }
            cont.invokeOnCancellation { handler.post { release() } }
            player.prepareAsync()
        }
    }

    private companion object {
        const val TICK_MS = 33L // ~30 quadros por segundo
    }
}
