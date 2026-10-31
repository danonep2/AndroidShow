package com.androidshow.alexa.voice

import android.os.SystemClock
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.net.Http
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Microfone como corpo de requisição: PCM 16 bits, 16 kHz, mono (AUDIO_L16_RATE_16000_CHANNELS_1).
 * Escreve primeiro o [preroll] (áudio anterior à detecção da palavra de ativação) e depois os
 * quadros ao vivo do [Microphone], direto no socket.
 */
class AudioCapture(
    private val mic: Microphone,
    private val preroll: List<ShortArray> = emptyList(),
    private val maxDurationMs: Long = 10_000,
    /** Em builds de debug, cópia do PCM enviado para análise. */
    private val debugCopy: File? = null,
) : RequestBody() {
    @Volatile private var stopped = false
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val listener = Microphone.Listener { queue.offer(it.copyOf()) }

    // Assina já na criação: nenhum quadro se perde entre a detecção e o início do envio HTTP.
    init {
        mic.add(listener)
    }

    fun stop() {
        stopped = true
        mic.remove(listener)
    }

    override fun contentType(): MediaType = Http.OCTET

    override fun writeTo(sink: BufferedSink) {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + maxDurationMs
        val copy = debugCopy?.outputStream()?.buffered()
        val bytes = ByteArray(Microphone.FRAME_SAMPLES * 2)
        var total = 0L
        var peak = 0
        var sumSquares = 0.0
        val vad = EndOfSpeech()
        var reason = "limite de tempo"

        fun write(frame: ShortArray): Double {
            var squares = 0.0
            frame.forEachIndexed { i, s ->
                bytes[i * 2] = s.toByte()
                bytes[i * 2 + 1] = (s.toInt() shr 8).toByte()
                val a = abs(s.toInt())
                if (a > peak) peak = a
                squares += a.toDouble() * a
            }
            sink.write(bytes, 0, frame.size * 2)
            copy?.write(bytes, 0, frame.size * 2)
            total += frame.size * 2
            sumSquares += squares
            return squares
        }

        try {
            preroll.forEach(::write)
            sink.flush()
            while (!stopped && SystemClock.elapsedRealtime() < deadline) {
                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val squares = write(frame)
                sink.flush()
                when (vad.feed(sqrt(squares / frame.size).toInt())) {
                    EndOfSpeech.Result.Ended -> { reason = "fim da fala (local)"; break }
                    EndOfSpeech.Result.NoSpeech -> { reason = "sem fala"; break }
                    EndOfSpeech.Result.Continue -> Unit
                }
            }
            if (stopped && reason == "limite de tempo") reason = "parado (StopCapture ou toque)"
        } finally {
            stop()
            copy?.close()
            val samples = (total / 2).coerceAtLeast(1)
            DiagLog.i(
                "mic",
                "fim ($reason): ${SystemClock.elapsedRealtime() - started} ms, $total bytes " +
                    "(preroll ${preroll.size * Microphone.FRAME_MS} ms), pico=$peak, rms=${sqrt(sumSquares / samples).toInt()}",
            )
        }
    }

    companion object {
        const val FORMAT = "AUDIO_L16_RATE_16000_CHANNELS_1"
    }
}
