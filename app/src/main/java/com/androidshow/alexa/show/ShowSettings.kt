package com.androidshow.alexa.show

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Uma categoria de cartão da home (injectedMetadata.name do UpdateData, ex.: "WeatherAlerts"). */
data class ShowCategory(val id: String, val label: String, val description: String? = null)

/** Configuração do modo Show. */
data class ShowConfig(
    /** true: a home gira pelos cartões da nuvem; false: fica parada no primeiro. */
    val carousel: Boolean = true,
    /** Categorias já vistas na home, para listar nos Ajustes mesmo antes de abrir o modo Show. */
    val categories: List<ShowCategory> = emptyList(),
    /** Ids de categoria escondidos da home. */
    val hidden: Set<String> = emptySet(),
)

class ShowSettings(context: Context) {
    private val prefs = context.getSharedPreferences("show", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<ShowConfig> = _config.asStateFlow()

    fun update(transform: (ShowConfig) -> ShowConfig) {
        val c = transform(_config.value)
        if (c == _config.value) return
        prefs.edit()
            .putBoolean("carousel", c.carousel)
            .putStringSet("categories", c.categories.map { listOf(it.id, it.label, it.description.orEmpty()).joinToString(SEP) }.toSet())
            .putStringSet("hidden", c.hidden)
            .apply()
        _config.value = c
    }

    private fun load(): ShowConfig {
        val d = ShowConfig()
        return ShowConfig(
            carousel = prefs.getBoolean("carousel", d.carousel),
            categories = prefs.getStringSet("categories", null).orEmpty().mapNotNull { s ->
                val p = s.split(SEP)
                if (p.size < 2) null else ShowCategory(p[0], p[1], p.getOrNull(2)?.ifEmpty { null })
            }.sortedBy { it.label },
            hidden = prefs.getStringSet("hidden", null).orEmpty().toSet(),
        )
    }

    private companion object {
        const val SEP = "\u001F"
    }
}
