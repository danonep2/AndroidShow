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
 * Uma instância por palavra; chamar [process] sempre na mesma thread.
 */
class OpenWakeWord(context: Context, modelFile: String) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
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
    }

    /** Processa um quadro de 80 ms e devolve a probabilidade da palavra (0 enquanto aquece). */
    fun process(frame: ShortArray): Float {
        require(frame.size == Microphone.FRAME_SAMPLES)
        System.arraycopy(raw, Microphone.FRAME_SAMPLES, raw, 0, RAW_CONTEXT)
        for (i in frame.indices) raw[RAW_CONTEXT + i] = frame[i].toFloat()

        run(mel, melInput, raw, longArrayOf(1, raw.size.toLong())) { out ->
            @Suppress("UNCHECKED_CAST")
            val spec = (out as Array<Array<Array<FloatArray>>>)[0][0]
            for (row in spec) melFrames.addLast(FloatArray(MEL_BANDS) { row[it] / 10f + 2f })
        }
        while (melFrames.size > MAX_MEL_FRAMES) melFrames.removeFirst()

        val start = melFrames.size - EMBEDDING_WINDOW
        for (r in 0 until EMBEDDING_WINDOW) System.arraycopy(melFrames[start + r], 0, embeddingWindow, r * MEL_BANDS, MEL_BANDS)
        run(embedding, embeddingInput, embeddingWindow, longArrayOf(1, EMBEDDING_WINDOW.toLong(), MEL_BANDS.toLong(), 1)) { out ->
            @Suppress("UNCHECKED_CAST")
            features.addLast((out as Array<Array<Array<FloatArray>>>)[0][0][0].copyOf())
        }
        while (features.size > MAX_FEATURES) features.removeFirst()
        if (features.size < featureFrames) return 0f

        val first = features.size - featureFrames
        for (r in 0 until featureFrames) System.arraycopy(features[first + r], 0, classifierWindow, r * EMBEDDING_SIZE, EMBEDDING_SIZE)
        var score = 0f
        run(classifier, classifierInput, classifierWindow, longArrayOf(1, featureFrames.toLong(), EMBEDDING_SIZE.toLong())) { out ->
            @Suppress("UNCHECKED_CAST")
            score = (out as Array<FloatArray>)[0][0]
        }
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

        /** Modelos de palavra disponíveis nos assets (tudo exceto os dois modelos de base). */
        fun availableModels(context: Context): List<String> =
            context.assets.list(DIR).orEmpty()
                .filter { it.endsWith(".onnx") && it != "melspectrogram.onnx" && it != "embedding_model.onnx" }
                .sorted()
    }
}
