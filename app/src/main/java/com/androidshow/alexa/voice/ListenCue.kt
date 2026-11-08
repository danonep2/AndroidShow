package com.androidshow.alexa.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.net.Uri
import com.androidshow.alexa.R
import com.androidshow.alexa.diag.DiagLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Som curto que avisa que a Alexa começou a ouvir. Pode ser desligado ou trocado por um arquivo
 * do usuário (copiado para files/, porque a permissão do seletor acaba quando ele fecha).
 * O SoundPool decodifica o som uma vez, para ele sair sem atraso a cada chamada.
 */
class ListenCue(private val context: Context) {
    data class State(val enabled: Boolean, val custom: Boolean)

    private val prefs = context.getSharedPreferences("listen_cue", Context.MODE_PRIVATE)
    private val customFile = File(context.filesDir, "listen_cue_custom")
    private val _state = MutableStateFlow(
        State(prefs.getBoolean("enabled", true), prefs.getBoolean("custom", false) && customFile.exists())
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val loads = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private val ready = ConcurrentHashMap.newKeySet<Int>()
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
        .apply {
            setOnLoadCompleteListener { _, id, status ->
                if (status == 0) ready += id
                loads.getOrPut(id) { CompletableDeferred() }.complete(status == 0)
            }
        }

    @Volatile private var sound = load()

    /** Toca ao abrir o microfone, se estiver ligado. */
    fun play() {
        if (_state.value.enabled) preview()
    }

    /** Toca mesmo desligado (botão de ouvir nos ajustes). */
    fun preview() {
        val id = sound
        if (id in ready) pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        _state.value = _state.value.copy(enabled = on)
    }

    /** Usa o áudio escolhido; devolve uma mensagem de erro ou null se deu certo. */
    suspend fun setCustom(uri: Uri): String? = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "listen_cue_new")
        try {
            tryCustom(uri, temp)
        } catch (e: Exception) {
            DiagLog.w("som", "som de escuta não carregou", e)
            "Não foi possível ler este áudio."
        } finally {
            temp.delete()
        }
    }

    private suspend fun tryCustom(uri: Uri, temp: File): String? {
        val tooBig = "Arquivo grande demais (máximo 1 MB)."
        val input = context.contentResolver.openInputStream(uri) ?: return "Não foi possível abrir o arquivo."
        input.use { src -> temp.outputStream().use { dst -> src.copyTo(dst) } }
        if (temp.length() > MAX_BYTES) return tooBig
        val ms = MediaMetadataRetriever().run {
            try {
                setDataSource(temp.path)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                release()
            }
        } ?: return "Não foi possível ler este áudio."
        if (ms > MAX_MS) return "Escolha um som curto (até ${MAX_MS / 1000} s)."
        val test = pool.load(temp.path, 1)
        val ok = awaitLoad(test)
        pool.unload(test)
        forget(test)
        if (!ok) return "Não foi possível tocar este áudio."
        temp.copyTo(customFile, overwrite = true)
        prefs.edit().putBoolean("custom", true).apply()
        _state.value = _state.value.copy(custom = true)
        reload()
        awaitLoad(sound) // pronto para a prévia logo em seguida
        return null
    }

    private fun forget(id: Int) {
        loads.remove(id)
        ready.remove(id)
    }

    fun resetDefault() {
        customFile.delete()
        prefs.edit().putBoolean("custom", false).apply()
        _state.value = _state.value.copy(custom = false)
        reload()
    }

    private fun reload() {
        val old = sound
        sound = load()
        pool.unload(old)
        forget(old)
    }

    private fun load(): Int =
        if (_state.value.custom && customFile.exists()) pool.load(customFile.path, 1)
        else pool.load(context, R.raw.listen_start, 1)

    private suspend fun awaitLoad(id: Int) =
        withTimeoutOrNull(5_000) { loads.getOrPut(id) { CompletableDeferred() }.await() } ?: false

    private companion object {
        const val MAX_BYTES = 1_000_000
        const val MAX_MS = 5_000L
    }
}
