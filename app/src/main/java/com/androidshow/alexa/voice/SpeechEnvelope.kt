package com.androidshow.alexa.voice

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Curva de intensidade da fala: decodifica o MP3 e guarda o RMS de cada janela de [WINDOW_MS],
 * normalizado pelo pico (0..1). A tela usa a posição da reprodução para ler o valor do momento,
 * sem precisar do Visualizer (que depende do fabricante).
 */
object SpeechEnvelope {
    const val WINDOW_MS = 30

    suspend fun of(mp3: ByteArray): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ByteArraySource(mp3))
            val format = extractor.getTrackFormat(0)
            extractor.selectTrack(0)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            val windows = ArrayList<Float>()
            var sum = 0.0
            var count = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val window = rate * channels * WINDOW_MS / 1000
                    val samples = decoder.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    while (samples.hasRemaining()) {
                        val s = samples.get().toDouble()
                        sum += s * s
                        if (++count == window) {
                            windows += sqrt(sum / count).toFloat()
                            sum = 0.0
                            count = 0
                        }
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val peak = windows.maxOrNull()?.takeIf { it > 0f } ?: return FloatArray(0)
            // Expoente < 1 realça trechos médios: a fala não fica "pulando" só nos picos.
            return FloatArray(windows.size) { (windows[it] / peak).pow(0.7f) }
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    private const val TIMEOUT_US = 10_000L
}

/** MP3 em memória como fonte do MediaPlayer/MediaExtractor (sem arquivo temporário). */
internal class ByteArraySource(private val data: ByteArray) : MediaDataSource() {
    override fun getSize() = data.size.toLong()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size, data.size - position.toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun close() = Unit
}
