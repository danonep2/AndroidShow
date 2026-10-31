package com.androidshow.alexa.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.androidshow.alexa.diag.DiagLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dono único do AudioRecord. O detector de palavra de ativação e o envio do Recognize leem os
 * mesmos quadros, evitando dois AudioRecord concorrentes (que o Android pode silenciar).
 * Lê quadros de 80 ms (1280 amostras, o passo do openWakeWord) numa thread própria, só
 * enquanto houver ouvintes.
 */
class Microphone {
    fun interface Listener {
        /** Chamado na thread do microfone; [frame] é reutilizado: copie se for guardar. */
        fun onFrame(frame: ShortArray)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var thread: Thread? = null
    @Volatile private var running = false

    @Synchronized
    fun add(listener: Listener) {
        listeners += listener
        if (!running) start()
    }

    @Synchronized
    fun remove(listener: Listener) {
        listeners -= listener
        if (listeners.isEmpty()) running = false
    }

    private fun start() {
        running = true
        thread = Thread(::loop, "microphone").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @SuppressLint("MissingPermission") // só é iniciado com RECORD_AUDIO concedido
    private fun loop() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_SAMPLES * 2 * 8),
        )
        val frame = ShortArray(FRAME_SAMPLES)
        try {
            recorder.startRecording()
            DiagLog.i("mic", "aberto: state=${recorder.state} recording=${recorder.recordingState}")
            while (running) {
                var filled = 0
                while (filled < FRAME_SAMPLES && running) {
                    val n = recorder.read(frame, filled, FRAME_SAMPLES - filled)
                    if (n < 0) {
                        DiagLog.w("mic", "erro de leitura $n")
                        running = false
                        break
                    }
                    filled += n
                }
                if (filled == FRAME_SAMPLES) listeners.forEach { it.onFrame(frame) }
            }
        } finally {
            recorder.stop()
            recorder.release()
            DiagLog.i("mic", "fechado")
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1280 // 80 ms
        const val FRAME_MS = 80

        /** Volume do quadro em 0..1 (escala em dB: ~-47 dBFS = 0, ~-11 dBFS = 1), para animações. */
        fun level(frame: ShortArray): Float {
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            val rms = kotlin.math.sqrt(sum / frame.size).coerceAtLeast(1.0)
            return (20 * kotlin.math.log10(rms / LEVEL_FLOOR) / LEVEL_RANGE_DB).toFloat().coerceIn(0f, 1f)
        }

        private const val LEVEL_FLOOR = 150.0
        private const val LEVEL_RANGE_DB = 36.0
    }
}
