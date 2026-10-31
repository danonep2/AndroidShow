package com.androidshow.alexa.voice

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Toca o MP3 da fala da Alexa direto da memória (sem arquivo temporário). */
class SpeechPlayer {

    /** Suspende até a fala terminar. Cancelar interrompe a reprodução. */
    suspend fun play(mp3: ByteArray) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            var released = false
            fun release() {
                if (!released) {
                    released = true
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
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener {
                release()
                if (cont.isActive) cont.resume(Unit)
            }
            player.setOnErrorListener { _, _, _ ->
                release()
                if (cont.isActive) cont.resume(Unit)
                true
            }
            cont.invokeOnCancellation { player.post { release() } }
            player.prepareAsync()
        }
    }

    private fun MediaPlayer.post(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private class ByteArraySource(private val data: ByteArray) : MediaDataSource() {
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
