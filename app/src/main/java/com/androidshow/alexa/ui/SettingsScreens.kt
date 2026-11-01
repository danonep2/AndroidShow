package com.androidshow.alexa.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.androidshow.alexa.voice.wake.OpenWakeWord
import com.androidshow.alexa.voice.wake.WakeWordConfig
import com.androidshow.alexa.voice.wake.WakeWordService

@Composable
fun SettingsScreen(
    session: Session,
    alexa: AlexaService,
    onBack: () -> Unit,
    onVoiceActivation: () -> Unit,
    onHistory: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val connection by alexa.connection.collectAsStateWithLifecycle()
    val wake by context.graph.wakeWord.config.collectAsStateWithLifecycle()
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
        section(header = "Palavra de ativação") {
            models.forEach { m ->
                IosRow(labelFor(m), subtitle = m, onClick = { apply(config.copy(model = m, label = labelFor(m))) }, trailing = {
                    if (m == config.model) Icon(Icons.Filled.Check, null, tint = Ios.Tint)
                })
            }
        }
        section(footer = "Envia a palavra junto com o áudio para a Amazon confirmar. Só funciona com palavras oficiais, como “Alexa”.") {
            IosRow("Verificar na nuvem", trailing = {
                IosSwitch(config.cloudVerified) { apply(config.copy(cloudVerified = it)) }
            })
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
