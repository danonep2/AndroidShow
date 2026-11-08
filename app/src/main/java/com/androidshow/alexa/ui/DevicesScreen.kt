package com.androidshow.alexa.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidshow.alexa.devices.Device
import com.androidshow.alexa.devices.DevicesRepository
import com.androidshow.alexa.ui.ios.Ios
import com.androidshow.alexa.ui.ios.IosPage
import com.androidshow.alexa.ui.ios.IosRow
import com.androidshow.alexa.ui.ios.IosSwitch
import com.androidshow.alexa.ui.ios.section

private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF453A)
private val Lit = Color(0xFFFFD60A)

/** Dispositivos da conta em cards com rolagem horizontal, um carrossel por seção. Tocar abre o detalhe. */
@Composable
fun DevicesScreen(devices: DevicesRepository, onBack: () -> Unit, onOpen: (Device) -> Unit) {
    val state by devices.state.collectAsStateWithLifecycle()
    var showOffline by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { devices.refresh() }
    CommandErrors(devices)

    IosPage("Dispositivos", back = "Início", onBack = onBack) {
        if (state.loading && state.echos.isEmpty() && state.appliances.isEmpty()) {
            message("Carregando…")
            return@IosPage
        }
        // Começa só com os online; os offline (e desativados) ficam atrás do "ver dispositivos offline".
        val (appliancesOn, appliancesOff) = state.appliances.sortedBy { it.name.lowercase() }.partition(::isOnline)
        val (echosOn, echosOff) = state.echos.sortedBy { it.name.lowercase() }.partition(::isOnline)

        carousel("Casa inteligente", appliancesOn, state.appliancesError, onOpen)
        carousel("Echos e telas", echosOn, state.echosError, onOpen)

        val offline = appliancesOff.size + echosOff.size
        if (offline == 0) return@IosPage
        section {
            IosRow(
                if (showOffline) "Ocultar dispositivos offline" else "Ver dispositivos offline",
                titleColor = Ios.Tint, value = "$offline", chevron = false,
                onClick = { showOffline = !showOffline },
            )
        }
        if (showOffline) {
            if (appliancesOff.isNotEmpty()) carousel("Casa inteligente offline", appliancesOff, null, onOpen)
            if (echosOff.isNotEmpty()) carousel("Echos e telas offline", echosOff, null, onOpen)
        }
    }
}

/** Online quando a resposta diz isso; desconhecido também conta, para não esconder o que pode funcionar. */
private fun isOnline(d: Device) = d.enabled && d.online != false

private fun LazyListScope.carousel(title: String, list: List<Device>, error: String?, onOpen: (Device) -> Unit) {
    item {
        Text(
            title.uppercase(), color = Ios.SecondaryLabel, fontSize = 13.sp,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 22.dp, bottom = 7.dp),
        )
    }
    item {
        val empty = when {
            error != null -> "Não foi possível carregar: $error"
            list.isEmpty() -> "Nenhum dispositivo online."
            else -> null
        }
        if (empty != null) {
            Text(empty, color = Ios.SecondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp))
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { d -> DeviceCard(d) { onOpen(d) } }
            }
        }
    }
}

@Composable
private fun DeviceCard(d: Device, onClick: () -> Unit) {
    val on = d.power == true && isOnline(d)
    Column(
        Modifier
            .width(150.dp).height(136.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Color(0xFF2C2C2E) else Ios.Group)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (on) lightColor(d).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(d), null, tint = if (on) lightColor(d) else Ios.SecondaryLabel, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(d.name, color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(summary(d), color = if (isOnline(d)) Ios.SecondaryLabel else Red, fontSize = 13.sp, maxLines = 1)
    }
}

/** Uma linha de estado: "Ligada · 80%", "Desligada", "Online", "Offline". */
private fun summary(d: Device): String = when {
    !d.enabled -> "Desativado"
    d.online == false -> "Offline"
    d.power == true -> listOfNotNull("Ligado", d.brightness?.let { "$it%" }).joinToString(" · ")
    d.power == false -> "Desligado"
    d.online == true -> "Online"
    else -> "—"
}

private fun iconFor(d: Device): ImageVector = when {
    d.kind == Device.Kind.Echo -> AppIcons.Speaker
    d.isLight -> AppIcons.Bulb
    else -> AppIcons.Devices
}

/** Cor da luz para o ícone: a cor atual quando saturada, senão amarelo de lâmpada acesa. */
private fun lightColor(d: Device): Color {
    val h = d.hue
    val s = d.saturation
    return if (h != null && s != null && s > 0.15f) Color.hsv(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), 1f) else Lit
}

/** Mostra um aviso quando um comando falha. */
@Composable
private fun CommandErrors(devices: DevicesRepository) {
    val context = LocalContext.current
    val state by devices.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.commandError) {
        state.commandError?.let {
            Toast.makeText(context, "Não funcionou — $it", Toast.LENGTH_LONG).show()
            devices.clearCommandError()
        }
    }
}

// ---------- Detalhe ----------

/** Nome aceito pela Alexa em setColor e o HSB que ele representa. */
private class NamedColor(val api: String, val label: String, val hue: Float, val saturation: Float)

private val COLORS = listOf(
    NamedColor("red", "Vermelho", 0f, 1f),
    NamedColor("orange", "Laranja", 30f, 1f),
    NamedColor("yellow", "Amarelo", 60f, 1f),
    NamedColor("green", "Verde", 120f, 1f),
    NamedColor("cyan", "Ciano", 180f, 1f),
    NamedColor("blue", "Azul", 240f, 1f),
    NamedColor("purple", "Roxo", 275f, 1f),
    NamedColor("magenta", "Magenta", 300f, 1f),
    NamedColor("pink", "Rosa", 330f, 0.5f),
)

private class Temperature(val api: String, val label: String, val kelvin: Int, val swatch: Color)

private val TEMPERATURES = listOf(
    Temperature("warm_white", "Quente", 2200, Color(0xFFFFB46B)),
    Temperature("soft_white", "Suave", 2700, Color(0xFFFFC98E)),
    Temperature("white", "Branco", 4000, Color(0xFFFFE7CC)),
    Temperature("daylight_white", "Dia", 5500, Color(0xFFFFF6ED)),
    Temperature("cool_white", "Frio", 7000, Color(0xFFDDE8FF)),
)

/** Gerenciamento de um aparelho: liga/desliga, brilho, cores e temperatura de cor, conforme ele aceita. */
@Composable
fun DeviceDetailScreen(devices: DevicesRepository, id: String, onBack: () -> Unit) {
    val state by devices.state.collectAsStateWithLifecycle()
    // O nome também serve de chave: o atalho de debug abre o detalhe por nome.
    val d = (state.appliances + state.echos).let { all -> all.find { it.id == id } ?: all.find { it.name == id } }
    LaunchedEffect(id) {
        // Aberto direto (debug ou o app recriado): a lista ainda não foi carregada.
        if (state.appliances.isEmpty() && state.echos.isEmpty()) devices.refresh()
        else if (d?.kind == Device.Kind.SmartHome) devices.refreshDevice(d.id)
    }
    CommandErrors(devices)

    IosPage(d?.name ?: "Dispositivo", back = "Dispositivos", onBack = onBack) {
        if (d == null) {
            message("Dispositivo não encontrado.")
            return@IosPage
        }
        val usable = isOnline(d)
        if (usable && d.canPower) item { PowerButton(d, devices) }

        if (usable && d.canDim) section(header = "Brilho") { BrightnessRow(d, devices) }

        if (usable && d.canColor) item {
            Text("CORES", color = Ios.SecondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp, top = 22.dp, bottom = 7.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(COLORS, key = { it.api }) { c ->
                    val selected = d.power == true && (d.saturation ?: 0f) > 0.15f && d.hue != null && hueDistance(d.hue, c.hue) < 15f
                    Swatch(Color.hsv(c.hue, c.saturation, 1f), c.label, selected) { devices.setColor(d, c.api, c.hue, c.saturation) }
                }
            }
        }

        if (usable && d.canColorTemperature) item {
            Text("BRANCOS", color = Ios.SecondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp, top = 22.dp, bottom = 7.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(TEMPERATURES, key = { it.api }) { t ->
                    val k = d.colorTemperatureK
                    val selected = d.power == true && (d.saturation ?: 0f) <= 0.15f && k != null &&
                        TEMPERATURES.minBy { kotlin.math.abs(it.kelvin - k) } == t
                    Swatch(t.swatch, t.label, selected) { devices.setColorTemperature(d, t.api, t.kelvin) }
                }
            }
        }

        section(header = "Informações", footer = if (!usable) "Controles indisponíveis enquanto o dispositivo estiver offline." else null) {
            IosRow("Conexão", trailing = { ConnectionStatus(d) })
            if (d.type.isNotEmpty()) IosRow("Tipo", value = d.type)
        }
    }
}

private fun hueDistance(a: Float, b: Float) = kotlin.math.abs(a - b).let { minOf(it, 360f - it) }

/** Botão grande de ligar/desligar, aceso na cor da luz. */
@Composable
private fun PowerButton(d: Device, devices: DevicesRepository) {
    val on = d.power == true
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) lightColor(d).copy(alpha = 0.18f) else Ios.Group)
            .clickable { devices.setPower(d, !on) }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(if (on) lightColor(d) else Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Power, null, tint = if (on) Color.Black else Ios.SecondaryLabel, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(if (on) "Ligado" else "Desligado", color = Ios.Label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (on) "Toque para desligar" else "Toque para ligar", color = Ios.SecondaryLabel, fontSize = 13.sp)
        }
        IosSwitch(on) { devices.setPower(d, it) }
    }
}

/** Brilho: a tela acompanha o dedo e o comando sai ao soltar. */
@Composable
private fun BrightnessRow(d: Device, devices: DevicesRepository) {
    var value by remember(d.id, d.brightness) { mutableFloatStateOf((d.brightness ?: 100).toFloat()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = value, onValueChange = { value = it }, valueRange = 1f..100f,
            onValueChangeFinished = { devices.setBrightness(d, value.toInt()) },
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Ios.Tint,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Text("${value.toInt()}%", color = Ios.SecondaryLabel, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
        Box(
            Modifier
                .size(48.dp).clip(CircleShape)
                .border(if (selected) 3.dp else 0.dp, if (selected) Color.White else Color.Transparent, CircleShape)
                .padding(if (selected) 5.dp else 0.dp)
                .clip(CircleShape).background(color)
                .clickable(onClick = onClick),
        )
        Text(label, color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ConnectionStatus(d: Device) {
    val label = when {
        !d.enabled -> "Desativado"
        d.online == true -> "Online"
        d.online == false -> "Offline"
        else -> "Desconhecido"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (d.online != null && d.enabled) Box(Modifier.size(8.dp).background(if (d.online) Green else Red, CircleShape))
        Text(label, color = Ios.SecondaryLabel, fontSize = 17.sp)
    }
}

private fun LazyListScope.message(text: String) = item {
    Text(
        text, color = Ios.SecondaryLabel, fontSize = 15.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 32.dp, end = 32.dp),
    )
}
