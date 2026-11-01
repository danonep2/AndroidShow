package com.androidshow.alexa.voice.wake

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Configuração da palavra de ativação. O nome exibido e o modelo são editáveis: uma palavra
 * própria é um arquivo .onnx treinado com o openWakeWord e importado pelo app (ver [CustomWakeWords]).
 */
data class WakeWordConfig(
    val enabled: Boolean = false,
    /** Arquivo do modelo em assets/wakeword ou "custom/<arquivo>" para um importado. */
    val model: String = "alexa_v0.1.onnx",
    /** Nome mostrado ao usuário. */
    val label: String = "Alexa",
    /** Probabilidade mínima para disparar (o openWakeWord recomenda 0,5). */
    val threshold: Float = 0.5f,
    /**
     * true: envia `initiator: WAKEWORD` com a palavra no áudio para a Amazon verificar (só palavras
     * oficiais, como "Alexa"). false: trata como toque e envia só o que vem depois da palavra
     * (necessário para palavras próprias).
     */
    val cloudVerified: Boolean = false,
)

class WakeWordSettings(context: Context) {
    private val prefs = context.getSharedPreferences("wakeword", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<WakeWordConfig> = _config.asStateFlow()

    fun update(transform: (WakeWordConfig) -> WakeWordConfig) {
        val c = transform(_config.value)
        prefs.edit()
            .putBoolean("enabled", c.enabled)
            .putString("model", c.model)
            .putString("label", c.label)
            .putFloat("threshold", c.threshold)
            .putBoolean("cloudVerified", c.cloudVerified)
            .apply()
        _config.value = c
    }

    private fun load(): WakeWordConfig {
        val d = WakeWordConfig()
        return WakeWordConfig(
            enabled = prefs.getBoolean("enabled", d.enabled),
            model = prefs.getString("model", d.model) ?: d.model,
            label = prefs.getString("label", d.label) ?: d.label,
            threshold = prefs.getFloat("threshold", d.threshold),
            cloudVerified = prefs.getBoolean("cloudVerified", d.cloudVerified),
        )
    }
}
