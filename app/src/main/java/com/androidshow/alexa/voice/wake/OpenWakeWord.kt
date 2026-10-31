package com.androidshow.alexa.voice.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.voice.Microphone
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * Porta do pipeline de streaming do openWakeWord (dscripka/openWakeWord, utils.AudioFeatures):
 *
 * 1. melspectrogram.onnx: últimos 1280 + 480 amostras → quadros mel de 32 bandas (`x/10 + 2`);
 * 2. embedding_model.onnx: janela de 76 quadros mel → vetor de 96 (um a cada 80 ms);
 * 3. modelo da palavra: últimos N vetores (16 no "alexa") → probabilidade 0..1.
 *
 * Otimizações sobre o original:
 * - XNNPACK (kernels ARM otimizados) em vez do executor padrão de CPU;
 * - porta de energia: com ~1 s seguido de silêncio, reaproveita o embedding de silêncio e pula
 *   embedding e classificador (a palavra não pode estar num trecho sem som).
 *
 * Uma instância por palavra; chamar [process] sempre na mesma thread.
 */
class OpenWakeWord(context: Context, modelFile: String, val xnnpack: Boolean = true) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        addConfigEntry("session.intra_op.allow_spinning", "0")
        if (xnnpack) addXnnpack(mapOf("intra_op_num_threads" to "1"))
    }

    /** Tempos acumulados por etapa, para medir o custo real no aparelho. */
    class Stats {
        var frames = 0
        var gated = 0
        var melNanos = 0L
        var embeddingNanos = 0L
        var classifierNanos = 0L

        fun summary(): String {
            val n = frames.coerceAtLeast(1)
            fun ms(v: Long) = "%.2f".format(v / n / 1e6)
            return "mel ${ms(melNanos)} + embedding ${ms(embeddingNanos)} + classificador ${ms(classifierNanos)} ms/quadro, " +
                "${gated * 100 / n}% dos quadros em silêncio (pulados)"
        }
    }

    var stats = Stats()
        private set

    private var noiseFloor = 200f
    private var hangover = 0
    private var silentEmbedding: FloatArray? = null
    private var gatedSinceRefresh = 0
    private val mel = session(context, "$DIR/melspectrogram.onnx")
    private val embedding = session(context, "$DIR/embedding_model.onnx")
    private val classifier = session(context, "$DIR/$modelFile")

    private val melInput = mel.inputNames.first()
    private val embeddingInput = embedding.inputNames.first()
    private val classifierInput = classifier.inputNames.first()
    private val featureFrames = (classifier.inputInfo.getValue(classifierInput).info as TensorInfo).shape[1].toInt()

    private val raw = FloatArray(Microphone.FRAME_SAMPLES + RAW_CONTEXT)
    private val melFrames = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private val embeddingWindow = FloatArray(EMBEDDING_WINDOW * MEL_BANDS)
    private val classifierWindow = FloatArray(featureFrames * EMBEDDING_SIZE)

    init {
        reset()
        DiagLog.i("wake", "modelo $modelFile carregado: entrada ${featureFrames}x$EMBEDDING_SIZE")
    }

    /** Descarta o histórico (ex.: depois de uma detecção, para não disparar de novo com o mesmo áudio). */
    fun reset() {
        raw.fill(0f)
        melFrames.clear()
        // O openWakeWord começa o buffer mel com 76 quadros de 1.
        repeat(EMBEDDING_WINDOW) { melFrames.addLast(FloatArray(MEL_BANDS) { 1f }) }
        features.clear()
        hangover = HANGOVER_FRAMES
    }

    fun resetStats(): Stats = stats.also { stats = Stats() }

    /** Processa um quadro de 80 ms e devolve a probabilidade da palavra (0 enquanto aquece). */
    fun process(frame: ShortArray): Float {
        require(frame.size == Microphone.FRAME_SAMPLES)
        stats.frames++
        System.arraycopy(raw, Microphone.FRAME_SAMPLES, raw, 0, RAW_CONTEXT)
        var squares = 0.0
        for (i in frame.indices) {
            val v = frame[i].toFloat()
            raw[RAW_CONTEXT + i] = v
            squares += v * v
        }
        val rms = kotlin.math.sqrt(squares / frame.size).toFloat()
        val loud = rms > maxOf(noiseFloor * GATE_RATIO, MIN_GATE_RMS)
        if (loud) hangover = HANGOVER_FRAMES else {
            if (hangover > 0) hangover--
            noiseFloor = noiseFloor * 0.95f + rms * 0.05f
        }

        // O mel roda sempre: a janela do embedding precisa dos últimos 760 ms reais quando a voz voltar.
        var t = System.nanoTime()
        run(mel, melInput, raw, longArrayOf(1, raw.size.toLong())) { out ->
            @Suppress("UNCHECKED_CAST")
            val spec = (out as Array<Array<Array<FloatArray>>>)[0][0]
            for (row in spec) melFrames.addLast(FloatArray(MEL_BANDS) { row[it] / 10f + 2f })
        }
        while (melFrames.size > MAX_MEL_FRAMES) melFrames.removeFirst()
        stats.melNanos += System.nanoTime() - t

        val silent = hangover == 0
        val cached = silentEmbedding
        if (silent && cached != null && features.size >= featureFrames && gatedSinceRefresh < REFRESH_SILENCE_FRAMES) {
            gatedSinceRefresh++
            features.addLast(cached)
            while (features.size > MAX_FEATURES) features.removeFirst()
            stats.gated++
            return 0f
        }

        t = System.nanoTime()
        val start = melFrames.size - EMBEDDING_WINDOW
        for (r in 0 until EMBEDDING_WINDOW) System.arraycopy(melFrames[start + r], 0, embeddingWindow, r * MEL_BANDS, MEL_BANDS)
        run(embedding, embeddingInput, embeddingWindow, longArrayOf(1, EMBEDDING_WINDOW.toLong(), MEL_BANDS.toLong(), 1)) { out ->
            @Suppress("UNCHECKED_CAST")
            val e = (out as Array<Array<Array<FloatArray>>>)[0][0][0].copyOf()
            features.addLast(e)
            // Atualiza o embedding de silêncio de tempos em tempos (o ruído do ambiente muda).
            if (silent) {
                silentEmbedding = e
                gatedSinceRefresh = 0
            }
        }
        while (features.size > MAX_FEATURES) features.removeFirst()
        stats.embeddingNanos += System.nanoTime() - t
        if (features.size < featureFrames) return 0f
        if (silent) return 0f

        t = System.nanoTime()

        val first = features.size - featureFrames
        for (r in 0 until featureFrames) System.arraycopy(features[first + r], 0, classifierWindow, r * EMBEDDING_SIZE, EMBEDDING_SIZE)
        var score = 0f
        run(classifier, classifierInput, classifierWindow, longArrayOf(1, featureFrames.toLong(), EMBEDDING_SIZE.toLong())) { out ->
            @Suppress("UNCHECKED_CAST")
            score = (out as Array<FloatArray>)[0][0]
        }
        stats.classifierNanos += System.nanoTime() - t
        return score
    }

    override fun close() {
        classifier.close()
        embedding.close()
        mel.close()
        options.close()
    }

    private inline fun run(session: OrtSession, input: String, data: FloatArray, shape: LongArray, read: (Any) -> Unit) {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(input to tensor)).use { result -> read(result[0].value) }
        }
    }

    private fun session(context: Context, asset: String): OrtSession =
        env.createSession(context.assets.open(asset).use { it.readBytes() }, options)

    companion object {
        const val DIR = "wakeword"
        private const val RAW_CONTEXT = 160 * 3
        private const val MEL_BANDS = 32
        private const val EMBEDDING_WINDOW = 76
        private const val EMBEDDING_SIZE = 96
        private const val MAX_MEL_FRAMES = 10 * 97
        private const val MAX_FEATURES = 120
        /** ~1 s sem som: cobre a janela de 760 ms do embedding. */
        private const val HANGOVER_FRAMES = 12
        private const val GATE_RATIO = 2f
        private const val MIN_GATE_RMS = 150f
        /** Recalcula o embedding de silêncio a cada ~4 s de silêncio contínuo. */
        private const val REFRESH_SILENCE_FRAMES = 50

        /** Compara o custo por quadro com e sem XNNPACK no próprio aparelho (só em debug). */
        fun benchmark(context: Context, modelFile: String, frames: Int = 150) {
            val rnd = java.util.Random(1)
            val audio = List(frames) { ShortArray(Microphone.FRAME_SAMPLES) { (rnd.nextGaussian() * 2000).toInt().toShort() } }
            for (accel in listOf(false, true)) {
                OpenWakeWord(context, modelFile, accel).use { e ->
                    audio.take(20).forEach { e.process(it) } // aquecimento
                    e.resetStats()
                    val t0 = System.nanoTime()
                    audio.forEach { e.process(it) }
                    val total = (System.nanoTime() - t0) / frames / 1e6
                    DiagLog.i("wake", "benchmark ${if (accel) "XNNPACK" else "CPU padrão"}: ${"%.2f".format(total)} ms/quadro (${e.stats.summary()})")
                }
            }
        }

        /** Modelos de palavra disponíveis nos assets (tudo exceto os dois modelos de base). */
        fun availableModels(context: Context): List<String> =
            context.assets.list(DIR).orEmpty()
                .filter { it.endsWith(".onnx") && it != "melspectrogram.onnx" && it != "embedding_model.onnx" }
                .sorted()
    }
}
