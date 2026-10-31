package com.androidshow.alexa.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidshow.alexa.graph
import com.androidshow.alexa.voice.wake.OpenWakeWord
import com.androidshow.alexa.voice.wake.WakeWordConfig
import com.androidshow.alexa.voice.wake.WakeWordService

/** Liga/desliga a palavra de ativação e escolhe o modelo. Edição do nome exibido fica para depois. */
@Composable
fun WakeWordPanel() {
    val context = LocalContext.current
    val settings = context.graph.wakeWord
    val config by settings.config.collectAsStateWithLifecycle()
    val models = remember { OpenWakeWord.availableModels(context) }
    var menu by remember { mutableStateOf(false) }

    fun apply(c: WakeWordConfig) {
        settings.update { c }
        if (c.enabled) WakeWordService.restart(context) else WakeWordService.stop(context)
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) apply(config.copy(enabled = true))
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ativação por voz")
                Text("Palavra: ${config.label}", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { on ->
                    if (!on) {
                        apply(config.copy(enabled = false))
                    } else {
                        val needed = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissions.launch(needed.toTypedArray())
                    }
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { menu = true }) { Text("Modelo: ${config.model}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = {
                            menu = false
                            apply(config.copy(model = m, label = labelFor(m)))
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Checkbox(checked = config.cloudVerified, onCheckedChange = { apply(config.copy(cloudVerified = it)) })
            Text("Verificar na nuvem", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** "alexa_v0.1.onnx" → "Alexa"; "jerson.onnx" → "Jerson". */
private fun labelFor(model: String) =
    model.substringBefore('.').substringBefore("_v").replace('_', ' ').replaceFirstChar { it.uppercase() }
