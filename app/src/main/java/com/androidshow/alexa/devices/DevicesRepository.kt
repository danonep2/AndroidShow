package com.androidshow.alexa.devices

import com.androidshow.alexa.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Dispositivos da conta para a tela. Echos e casa inteligente carregam em paralelo e falham
 * separados; os comandos atualizam a tela na hora e conferem o estado real logo depois.
 */
class DevicesRepository(private val api: DevicesApi, private val scope: CoroutineScope) {
    data class State(
        val loading: Boolean = false,
        val echos: List<Device> = emptyList(),
        val appliances: List<Device> = emptyList(),
        val echosError: String? = null,
        val appliancesError: String? = null,
        /** Último comando que falhou, para um aviso na tela. */
        val commandError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val pending = mutableMapOf<String, Job>()

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        scope.launch {
            val echos = async { runCatching { api.echos() } }
            val appliances = async { runCatching { api.appliances() } }
            val e = echos.await()
            val a = appliances.await()
            e.exceptionOrNull()?.let { DiagLog.w("devices", "Echos falharam", it) }
            a.exceptionOrNull()?.let { DiagLog.w("devices", "Casa inteligente falhou", it) }
            DiagLog.i("devices", "Echos: ${e.getOrNull()?.size ?: "erro"}; casa inteligente: ${a.getOrNull()?.size ?: "erro"}")
            _state.update {
                it.copy(
                    loading = false,
                    echos = e.getOrDefault(it.echos),
                    appliances = a.getOrDefault(it.appliances),
                    echosError = e.exceptionOrNull()?.let(::message),
                    appliancesError = a.exceptionOrNull()?.let(::message),
                )
            }
            readStates(_state.value.appliances.filter { it.canPower || it.canDim }.map { it.id })
        }
    }

    fun setPower(d: Device, on: Boolean) =
        command(d, mapOf("action" to if (on) "turnOn" else "turnOff")) { it.copy(power = on) }

    fun setBrightness(d: Device, value: Int) =
        command(d, mapOf("action" to "setBrightness", "brightness" to value)) { it.copy(brightness = value, power = true) }

    /** [color] é um dos nomes que a Alexa aceita (red, blue…); a tela já sabe o HSB de cada um. */
    fun setColor(d: Device, color: String, hue: Float, saturation: Float) =
        command(d, mapOf("action" to "setColor", "colorName" to color)) {
            it.copy(hue = hue, saturation = saturation, colorTemperatureK = null, power = true)
        }

    /** [name] é warm_white, soft_white, white, daylight_white ou cool_white. */
    fun setColorTemperature(d: Device, name: String, kelvin: Int) =
        command(d, mapOf("action" to "setColorTemperature", "colorTemperatureName" to name)) {
            it.copy(colorTemperatureK = kelvin, saturation = 0f, power = true)
        }

    /** Relê o estado de um aparelho (ao abrir o detalhe). */
    fun refreshDevice(id: String) {
        scope.launch { readStates(listOf(id)) }
    }

    fun clearCommandError()= _state.update { it.copy(commandError = null) }

    /** Aplica [optimistic] na hora; o último comando de cada aparelho cancela o anterior (arrastar o brilho). */
    private fun command(d: Device, parameters: Map<String, Any>, optimistic: (Device) -> Device) {
        edit(d.id, optimistic)
        pending.remove(d.id)?.cancel()
        pending[d.id] = scope.launch {
            delay(250)
            try {
                api.control(d.id, parameters)
                DiagLog.i("devices", "${parameters["action"]} em ${d.name}: ok")
            } catch (e: CancellationException) {
                throw e // substituído por um comando mais novo: não é falha
            } catch (e: Exception) {
                DiagLog.w("devices", "${parameters["action"]} em ${d.name} falhou", e)
                _state.update { it.copy(commandError = "${d.name}: ${message(e)}") }
            }
            // Como o app Windows: relê o estado logo depois do comando.
            delay(800)
            readStates(listOf(d.id))
        }
    }

    private suspend fun readStates(ids: List<String>) {
        val states = runCatching { api.states(ids) }
            .onFailure { DiagLog.w("devices", "Leitura de estado falhou", it) }
            .getOrNull() ?: return
        states.forEach { (id, s) ->
            edit(id) { d ->
                d.copy(
                    power = s.power ?: d.power,
                    brightness = s.brightness ?: d.brightness,
                    online = s.online ?: d.online,
                    hue = s.hue ?: d.hue,
                    saturation = s.saturation ?: d.saturation,
                    colorTemperatureK = s.colorTemperatureK ?: d.colorTemperatureK,
                )
            }
        }
    }

    private fun edit(id: String, change: (Device) -> Device) = _state.update { st ->
        st.copy(appliances = st.appliances.map { if (it.id == id) change(it) else it })
    }

    private fun message(t: Throwable) = t.message?.take(160) ?: t::class.simpleName.orEmpty()
}
