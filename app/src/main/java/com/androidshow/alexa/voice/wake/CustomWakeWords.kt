package com.androidshow.alexa.voice.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import com.androidshow.alexa.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.FloatBuffer
import java.util.UUID

/** Palavra importada pelo usuário: [model] é o identificador usado em [WakeWordConfig.model]. */
@Serializable
data class CustomWakeWord(val model: String, val label: String)

/**
 * Modelos de palavra importados (.onnx do openWakeWord). Os modelos de fábrica continuam em
 * assets/wakeword; os importados ficam em filesDir/wakeword e são referenciados como "custom/<arquivo>".
 */
class CustomWakeWords(private val context: Context) {
    private val prefs = context.getSharedPreferences("wakeword", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, OpenWakeWord.DIR)
    private val _words = MutableStateFlow(load())
    val words: StateFlow<List<CustomWakeWord>> = _words.asStateFlow()

    class InvalidModel(message: String) : Exception(message)

    /** Copia, valida e registra o modelo. Falha com [InvalidModel] e uma mensagem para o usuário. */
    suspend fun import(uri: Uri, label: String): Result<CustomWakeWord> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) throw InvalidModel("Arquivo grande demais (máximo ${MAX_BYTES / 1_000_000} MB).")
                }
                out.toByteArray()
            } ?: throw InvalidModel("Não deu para abrir o arquivo.")
            validate(bytes)
            dir.mkdirs()
            val name = "${UUID.randomUUID()}.onnx"
            File(dir, name).writeBytes(bytes)
            val word = CustomWakeWord(CUSTOM_PREFIX + name, label.trim())
            save(_words.value + word)
            DiagLog.i("wake", "modelo importado: \"${word.label}\" (${bytes.size / 1024} KB)")
            word
        }.onFailure { if (it !is InvalidModel) DiagLog.w("wake", "falha ao importar modelo", it) }
    }

    fun remove(model: String) {
        File(dir, model.removePrefix(CUSTOM_PREFIX)).delete()
        save(_words.value.filterNot { it.model == model })
    }

    /**
     * Confere que é um classificador do openWakeWord: entrada [1, N, 96] (N vetores de embedding)
     * e saída com uma probabilidade, como o alexa_v0.1.onnx. Roda uma inferência de teste.
     */
    private fun validate(bytes: ByteArray) {
        val env = OrtEnvironment.getEnvironment()
        val session = runCatching { env.createSession(bytes) }
            .getOrElse { throw InvalidModel("O arquivo não é um modelo ONNX válido.") }
        session.use { s ->
            if (s.inputNames.size != 1) throw InvalidModel("O modelo deve ter uma única entrada.")
            val input = s.inputNames.first()
            val shape = (s.inputInfo.getValue(input).info as? TensorInfo)?.shape
            if (shape == null || shape.size != 3 || shape[2] != 96L || shape[1] <= 0) {
                throw InvalidModel(
                    "Formato incompatível: a entrada deve ser [1, N, 96] e veio ${shape?.contentToString() ?: "desconhecida"}. " +
                        "Use um classificador treinado com o openWakeWord."
                )
            }
            val frames = shape[1]
            val score = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray((frames * 96).toInt())), longArrayOf(1, frames, 96)).use { t ->
                s.run(mapOf(input to t)).use { r -> ((r[0].value as? Array<*>)?.firstOrNull() as? FloatArray)?.firstOrNull() }
            } ?: throw InvalidModel("A saída do modelo deve ser uma probabilidade [1, 1].")
            if (score.isNaN()) throw InvalidModel("O modelo devolveu um valor inválido no teste.")
        }
    }

    private fun save(list: List<CustomWakeWord>) {
        prefs.edit().putString(KEY, Json.encodeToString(list)).apply()
        _words.value = list
    }

    private fun load(): List<CustomWakeWord> =
        prefs.getString(KEY, null)?.let { runCatching { Json.decodeFromString<List<CustomWakeWord>>(it) }.getOrNull() }
            .orEmpty()
            .filter { File(dir, it.model.removePrefix(CUSTOM_PREFIX)).exists() }

    companion object {
        const val CUSTOM_PREFIX = "custom/"
        private const val KEY = "custom"
        private const val MAX_BYTES = 10_000_000

        fun isCustom(model: String) = model.startsWith(CUSTOM_PREFIX)

        /** Bytes de um modelo de palavra: importado (filesDir) ou de fábrica (assets). */
        fun read(context: Context, model: String): ByteArray =
            if (isCustom(model)) File(File(context.filesDir, OpenWakeWord.DIR), model.removePrefix(CUSTOM_PREFIX)).readBytes()
            else context.assets.open("${OpenWakeWord.DIR}/$model").use { it.readBytes() }
    }
}
