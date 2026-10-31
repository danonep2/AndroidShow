package com.androidshow.alexa.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.net.Http
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

/**
 * Microfone como corpo de requisição: PCM 16 bits, 16 kHz, mono (AUDIO_L16_RATE_16000_CHANNELS_1).
 * O áudio é gravado direto no socket em blocos de 100 ms, sem buffer intermediário.
 */
class AudioCapture(
    private val maxDurationMs: Long = 10_000,
    /** Em builds de debug, cópia do PCM enviado para análise. */
    private val debugCopy: File? = null,
) : RequestBody() {
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
        val started = SystemClock.elapsedRealtime()
        val deadline = started + maxDurationMs
        val copy = debugCopy?.outputStream()?.buffered()
        var total = 0L
        var peak = 0
        var sumSquares = 0.0
        var readError = 0
        try {
            recorder.startRecording()
            DiagLog.i("mic", "gravando: state=${recorder.state} recording=${recorder.recordingState} buffer=$minBuffer")
            while (!stopped && SystemClock.elapsedRealtime() < deadline) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n < 0) { readError = n; break }
                if (n > 0) {
                    sink.write(chunk, 0, n)
                    sink.flush()
                    copy?.write(chunk, 0, n)
                    total += n
                    for (i in 0 until n - 1 step 2) {
                        val s = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                        val a = kotlin.math.abs(s.toShort().toInt())
                        if (a > peak) peak = a
                        sumSquares += a.toDouble() * a
                    }
                }
            }
        } finally {
            stopped = true
            recorder.stop()
            recorder.release()
            copy?.close()
            val samples = (total / 2).coerceAtLeast(1)
            DiagLog.i(
                "mic",
                "fim: ${SystemClock.elapsedRealtime() - started} ms, $total bytes, pico=$peak, " +
                    "rms=${kotlin.math.sqrt(sumSquares / samples).toInt()}, erro=$readError",
            )
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
