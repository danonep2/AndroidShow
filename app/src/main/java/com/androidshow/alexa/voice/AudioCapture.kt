package com.androidshow.alexa.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.androidshow.alexa.net.Http
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Microfone como corpo de requisição: PCM 16 bits, 16 kHz, mono (AUDIO_L16_RATE_16000_CHANNELS_1).
 * O áudio é gravado direto no socket em blocos de 100 ms, sem buffer intermediário.
 */
class AudioCapture(private val maxDurationMs: Long = 10_000) : RequestBody() {
    @Volatile private var stopped = false

    fun stop() {
        stopped = true
    }

    override fun contentType(): MediaType = Http.OCTET

    @SuppressLint("MissingPermission") // a UI só inicia a captura com RECORD_AUDIO concedido
    override fun writeTo(sink: BufferedSink) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING,
            maxOf(minBuffer, CHUNK_BYTES * 4),
        )
        val chunk = ByteArray(CHUNK_BYTES)
        val deadline = SystemClock.elapsedRealtime() + maxDurationMs
        try {
            recorder.startRecording()
            while (!stopped && SystemClock.elapsedRealtime() < deadline) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n < 0) break
                if (n > 0) {
                    sink.write(chunk, 0, n)
                    sink.flush()
                }
            }
        } finally {
            stopped = true
            recorder.stop()
            recorder.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FORMAT = "AUDIO_L16_RATE_16000_CHANNELS_1"
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10 // 100 ms
    }
}
