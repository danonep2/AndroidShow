package com.androidshow.alexa.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Pede para o app ficar fora da otimização de bateria. Sem isso o sistema (o HyperOS em especial)
 * corta a rede do app com a tela apagada e a Alexa para de responder [Observado no Redmi].
 */
@SuppressLint("BatteryLife") // app pessoal; a escuta contínua é a função principal
@Composable
fun BackgroundPanel() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    if (exempt) return
    Column {
        Text(
            "Com a tela apagada o sistema pode cortar a internet do app. Permita o uso em segundo plano.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            )
        }) { Text("Permitir em segundo plano") }
    }
}
