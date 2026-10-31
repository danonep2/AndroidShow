package com.androidshow.alexa.voice.wake

import android.os.SystemClock
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.voice.Microphone

/**
 * Ouvinte do [Microphone] que roda o [OpenWakeWord] em cada quadro e guarda os últimos 2 s de
 * áudio. Ao detectar, entrega esse áudio (preroll) para o Recognize.
 */
class WakeWordDetector(
    private val engine: OpenWakeWord,
    private val config: WakeWordConfig,
    private val onDetected: (preroll: List<ShortArray>, score: Float) -> Unit,
) : Microphone.Listener {
    /** Pausado enquanto a Alexa ouve ou fala, para não disparar com a própria voz dela. */
    @Volatile var paused = false
        set(value) {
            if (field && !value) resumeAfter = SystemClock.elapsedRealtime() + RESUME_GRACE_MS
            field = value
        }

    private val ring = ArrayDeque<ShortArray>()
    private var resumeAfter = 0L
    private var peakScore = 0f
    private var frames = 0
    private var busyNanos = 0L

    override fun onFrame(frame: ShortArray) {
        ring.addLast(frame.copyOf())
        while (ring.size > RING_FRAMES) ring.removeFirst()
        if (paused || SystemClock.elapsedRealtime() < resumeAfter) return

        val t0 = System.nanoTime()
        val score = engine.process(frame)
        busyNanos += System.nanoTime() - t0
        if (score > peakScore) peakScore = score
        if (++frames == STATS_FRAMES) logStats()

        if (score >= config.threshold) {
            DiagLog.i("wake", "\"${config.label}\" detectada (score=${"%.2f".format(score)})")
            val preroll = ring.toList()
            engine.reset()
            resumeAfter = SystemClock.elapsedRealtime() + RESUME_GRACE_MS
            onDetected(preroll, score)
        }
    }

    /** A cada ~1 min: custo médio por quadro (quanto da janela de 80 ms o modelo ocupa) e maior score. */
    private fun logStats() {
        val avgMs = busyNanos / frames / 1e6
        DiagLog.i(
            "wake",
            "custo médio ${"%.2f".format(avgMs)} ms/quadro (${"%.1f".format(avgMs / Microphone.FRAME_MS * 100)}% de um núcleo), " +
                "maior score ${"%.2f".format(peakScore)}",
        )
        frames = 0
        busyNanos = 0
        peakScore = 0f
    }

    private companion object {
        const val RING_FRAMES = 25 // 2 s
        const val RESUME_GRACE_MS = 1_000L
        const val STATS_FRAMES = 750 // 60 s
    }
}
