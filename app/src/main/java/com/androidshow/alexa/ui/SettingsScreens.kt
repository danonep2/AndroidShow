package com.androidshow.alexa.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidshow.alexa.AlexaService
import com.androidshow.alexa.BuildConfig
import com.androidshow.alexa.auth.Session
import com.androidshow.alexa.avs.AvsClient
import com.androidshow.alexa.graph
import com.androidshow.alexa.ui.ios.Ios
import com.androidshow.alexa.ui.ios.IosGroup
import com.androidshow.alexa.ui.ios.IosPage
import com.androidshow.alexa.ui.ios.IosRow
import com.androidshow.alexa.ui.ios.IosSwitch
import com.androidshow.alexa.ui.ios.section
import com.androidshow.alexa.voice.wake.CustomWakeWord
import com.androidshow.alexa.voice.wake.OpenWakeWord
import com.androidshow.alexa.voice.wake.WakeWordConfig
import com.androidshow.alexa.voice.wake.WakeWordService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    session: Session,
    alexa: AlexaService,
    onBack: () -> Unit,
    onVoiceActivation: () -> Unit,
    onShowMode: () -> Unit,
    onHistory: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val connection by alexa.connection.collectAsStateWithLifecycle()
    val wake by context.graph.wakeWord.config.collectAsStateWithLifecycle()
    val show by context.graph.showSettings.config.collectAsStateWithLifecycle()
    val background = rememberBackgroundExemption()
    var confirmLogout by remember { mutableStateOf(false) }

    IosPage("Ajustes", back = "Início", onBack = onBack) {
        item { AccountCard(session) }
        section {
            IosRow(
                "Ativação por voz", icon = AppIcons.Mic, iconColor = Palette.Primary,
                value = if (wake.enabled) wake.label else "Desativada", onClick = onVoiceActivation,
            )
            IosRow(
                "Modo Show", icon = AppIcons.Screen, iconColor = Palette.Accent,
                value = if (show.carousel) "Carrossel" else "Fixo", onClick = onShowMode,
            )
            IosRow(
                "Conexão", icon = AppIcons.Cloud, iconColor = Palette.Secondary,
                value = when (connection) {
                    AvsClient.State.Connected -> "Conectada"
                    AvsClient.State.Connecting -> "Conectando…"
                    AvsClient.State.Disconnected -> "Desconectada"
                },
            )
        }
        section(
            footer = if (background.exempt) null
            else "Com a tela apagada o sistema pode cortar a internet do app e a Alexa para de responder.",
        ) {
            IosRow(
                "Segundo plano", icon = AppIcons.Battery, iconColor = Color(0xFF30D158),
                value = if (background.exempt) "Permitido" else "Restrito",
                onClick = if (background.exempt) null else background::request,
            )
        }
        section(header = "Diagnóstico") {
            IosRow("Histórico", icon = Icons.AutoMirrored.Filled.List, iconColor = Ios.Gray, onClick = onHistory)
            IosRow("Copiar diagnóstico", icon = AppIcons.Copy, iconColor = Ios.Gray, chevron = false, onClick = onCopyDiagnostics)
        }
        section(footer = "AndroidShow ${BuildConfig.VERSION_NAME}") {
            IosRow("Sair da conta", titleColor = Ios.Destructive, centered = true, onClick = { confirmLogout = true })
        }
        item {
            Text(
                buildAnnotatedString {
                    append("Feito por ")
                    withLink(LinkAnnotation.Url(AUTHOR_URL, TextLinkStyles(SpanStyle(color = Ios.Tint)))) { append("Iago Amorim") }
                },
                color = Ios.SecondaryLabel, fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 2.dp),
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            containerColor = Ios.Group,
            title = { Text("Sair da conta?", color = Ios.Label, fontWeight = FontWeight.SemiBold) },
            text = { Text("Este aparelho deixa de ser um dispositivo Alexa até você entrar de novo.", color = Ios.SecondaryLabel) },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Sair", color = Ios.Destructive) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Cancelar", color = Ios.Tint) }
            },
        )
    }
}

/** Cartão da conta no topo, como o do Apple ID. */
@Composable
private fun AccountCard(s: Session) {
    val name = s.customerName ?: "Conta Amazon"
    Box(Modifier.padding(top = 12.dp)) {
        IosGroup {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(60.dp).background(Brush.linearGradient(listOf(Palette.Accent, Palette.Primary, Palette.Secondary)), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.take(1).uppercase(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(name, color = Ios.Label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(s.marketplace.domain, color = Ios.SecondaryLabel, fontSize = 14.sp)
                    Text(s.deviceName ?: s.deviceSerial, color = Ios.SecondaryLabel, fontSize = 14.sp)
                }
            }
        }
    }
}

/** Liga/desliga a palavra de ativação e escolhe o modelo. */
@Composable
fun VoiceActivationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = context.graph.wakeWord
    val config by settings.config.collectAsStateWithLifecycle()
    val models = remember { OpenWakeWord.availableModels(context) }
    val custom by context.graph.customWakeWords.words.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CustomWakeWord?>(null) }

    fun apply(c: WakeWordConfig) {
        settings.update { c }
        if (c.enabled) WakeWordService.restart(context) else WakeWordService.stop(context)
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) apply(config.copy(enabled = true))
    }

    IosPage("Ativação por voz", back = "Ajustes", onBack = onBack) {
        section(footer = "Diga “${config.label}” para falar com a Alexa sem tocar na tela.") {
            IosRow("Ouvir “${config.label}”", trailing = {
                IosSwitch(config.enabled) { on ->
                    if (!on) {
                        apply(config.copy(enabled = false))
                    } else {
                        val needed = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissions.launch(needed.toTypedArray())
                    }
                }
            })
        }
        section(
            header = "Palavra de ativação",
            footer = "Palavras novas são modelos .onnx treinados com o openWakeWord. As adicionadas por você podem ser removidas.",
        ) {
            models.forEach { m ->
                IosRow(labelFor(m), subtitle = "Padrão do app", onClick = { apply(config.copy(model = m, label = labelFor(m))) }, trailing = {
                    if (m == config.model) Icon(Icons.Filled.Check, null, tint = Ios.Tint)
                })
            }
            custom.forEach { w ->
                // A Amazon só confirma palavras oficiais: palavra própria vai sempre como toque.
                IosRow(w.label, subtitle = "Adicionada por você", onClick = { apply(config.copy(model = w.model, label = w.label, cloudVerified = false)) }, trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (w.model == config.model) Icon(Icons.Filled.Check, null, tint = Ios.Tint)
                        IconButton(onClick = { removing = w }) { Icon(Icons.Filled.Delete, "Remover ${w.label}", tint = Ios.Gray) }
                    }
                })
            }
            IosRow("Adicionar nova palavra", icon = Icons.Filled.Add, iconColor = Palette.Primary, titleColor = Ios.Tint, chevron = false, onClick = { adding = true })
        }
        section(footer = "Envia a palavra junto com o áudio para a Amazon confirmar. Só funciona com palavras oficiais, como “Alexa”.") {
            IosRow("Verificar na nuvem", trailing = {
                IosSwitch(config.cloudVerified) { apply(config.copy(cloudVerified = it)) }
            })
        }
    }

    if (adding) {
        AddWakeWordDialog(
            onDismiss = { adding = false },
            onAdded = { w ->
                adding = false
                apply(config.copy(model = w.model, label = w.label, cloudVerified = false))
                Toast.makeText(context, "“${w.label}” adicionada e selecionada", Toast.LENGTH_SHORT).show()
            },
        )
    }

    removing?.let { w ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Ios.Group,
            title = { Text("Remover “${w.label}”?", color = Ios.Label, fontWeight = FontWeight.SemiBold) },
            text = { Text("O arquivo do modelo é apagado do aparelho. Se ela estiver em uso, o app volta para a palavra padrão.", color = Ios.SecondaryLabel) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    val active = w.model == config.model
                    context.graph.customWakeWords.remove(w.model)
                    if (active) models.firstOrNull()?.let { m -> apply(config.copy(model = m, label = labelFor(m))) }
                }) { Text("Remover", color = Ios.Destructive) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("Cancelar", color = Ios.Tint) }
            },
        )
    }
}

/** Ajustes da home do modo Show. */
@Composable
fun ShowModeSettingsScreen(onBack: () -> Unit, onCategories: () -> Unit) {
    val context = LocalContext.current
    val settings = context.graph.showSettings
    val config by settings.config.collectAsStateWithLifecycle()
    val shown = config.categories.count { it.id !in config.hidden }
    val wallpaper = context.graph.showWallpaper
    val image by wallpaper.image.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // O seletor de fotos do sistema não pede permissão de galeria.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (!wallpaper.set(uri)) Toast.makeText(context, "Não deu para abrir essa foto", Toast.LENGTH_SHORT).show()
        }
    }

    IosPage("Modo Show", back = "Ajustes", onBack = onBack) {
        section(footer = "Desativado, a home mostra só o primeiro cartão que chega ao abrir o modo Show, sem girar.") {
            IosRow("Ativar Carrossel", trailing = {
                IosSwitch(config.carousel) { on -> settings.update { it.copy(carousel = on) } }
            })
        }
        section(footer = "Escolha que tipos de cartão aparecem na home.") {
            IosRow(
                "Telas listadas",
                value = if (config.categories.isEmpty()) null else "$shown de ${config.categories.size}",
                onClick = onCategories,
            )
        }
        section(footer = "Desativado, o botão some do canto da tela e a Alexa é acionada só pela palavra de ativação.") {
            IosRow("Botão do microfone", trailing = {
                IosSwitch(config.micButton) { on -> settings.update { it.copy(micButton = on) } }
            })
        }
        section(footer = "Mostra na tela a frase que a Alexa entendeu enquanto ela responde.") {
            IosRow("Transcrever pedidos", trailing = {
                IosSwitch(config.transcription) { on -> settings.update { it.copy(transcription = on) } }
            })
        }
        section(header = "Relógio", footer = "A foto fica de fundo na tela do relógio, com o carrossel ligado ou não.") {
            IosRow(
                "Papel de parede", value = if (image == null) "Nenhum" else null,
                onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                trailing = image?.let { img ->
                    {
                        Image(
                            img, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    }
                },
            )
            if (image != null) {
                IosRow("Remover papel de parede", titleColor = Ios.Destructive, centered = true, onClick = wallpaper::clear)
            }
        }
    }
}

/** Liga e desliga cada categoria de cartão da home. */
@Composable
fun ShowCategoriesScreen(onBack: () -> Unit) {
    val settings = LocalContext.current.graph.showSettings
    val config by settings.config.collectAsStateWithLifecycle()

    IosPage("Telas listadas", back = "Modo Show", onBack = onBack) {
        section(
            footer = if (config.categories.size == 1) "Abra o modo Show uma vez para a Alexa enviar as outras telas disponíveis."
            else "O relógio é do próprio app; as outras telas vêm da sua conta Amazon e novas aparecem aqui quando a Alexa enviar.",
        ) {
            config.categories.forEach { c ->
                IosRow(c.label, subtitle = c.description, trailing = {
                    IosSwitch(c.id !in config.hidden) { on ->
                        settings.update { it.copy(hidden = if (on) it.hidden - c.id else it.hidden + c.id) }
                    }
                })
            }
        }
    }
}

/** Últimas diretivas e frases entendidas, a mais recente primeiro. */
@Composable
fun HistoryScreen(alexa: AlexaService, onBack: () -> Unit) {
    val log by alexa.log.collectAsStateWithLifecycle()
    IosPage("Histórico", back = "Ajustes", onBack = onBack) {
        if (log.isEmpty()) {
            item {
                Text(
                    "Nada por aqui ainda.", color = Ios.SecondaryLabel, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                )
            }
        } else {
            section { log.asReversed().forEach { IosRow(it) } }
        }
    }
}

/**
 * Isenção da otimização de bateria. Sem ela o sistema (o HyperOS em especial) corta a rede do
 * app com a tela apagada e a Alexa para de responder [Observado no Redmi].
 */
class BackgroundExemption(private val context: android.content.Context, val exempt: Boolean) {
    @SuppressLint("BatteryLife") // app pessoal; a escuta contínua é a função principal
    fun request() {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    }
}

@Composable
private fun rememberBackgroundExemption(): BackgroundExemption {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    return BackgroundExemption(context, exempt)
}

/** "alexa_v0.1.onnx" → "Alexa"; "jerson.onnx" → "Jerson". */
private fun labelFor(model: String) =
    model.substringBefore('.').substringBefore("_v").replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Página do autor, no rodapé dos Ajustes. */
private const val AUTHOR_URL = "https://iago.dev"
