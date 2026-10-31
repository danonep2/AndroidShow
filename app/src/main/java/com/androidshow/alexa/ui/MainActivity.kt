package com.androidshow.alexa.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidshow.alexa.AlexaService
import com.androidshow.alexa.auth.LoginActivity
import com.androidshow.alexa.avs.AvsClient
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.auth.Marketplace
import com.androidshow.alexa.auth.Session
import com.androidshow.alexa.graph
import com.androidshow.alexa.show.ShowModeActivity
import com.androidshow.alexa.voice.wake.WakeWordService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val session by graph.auth.session.collectAsStateWithLifecycle()
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("AndroidShow", style = MaterialTheme.typography.headlineMedium)
                        val s = session
                        if (s == null) {
                            LoginPanel { startActivity(LoginActivity.intent(this@MainActivity, it)) }
                        } else {
                            SessionPanel(s, graph.alexa, onLogout = graph.auth::logout)
                        }
                        TextButton(onClick = ::copyDiagnostics) { Text("Copiar diagnóstico") }
                    }
                }
            }
        }
    }

    /**
     * O Android 14+ só permite iniciar o serviço de microfone com o app visível; por isso ele é
     * (re)iniciado aqui e não no Application. Depois de iniciado, segue com a tela apagada.
     */
    override fun onResume() {
        super.onResume()
        if (graph.wakeWord.config.value.enabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) WakeWordService.start(this)
    }

    private fun copyDiagnostics() {
        val text = DiagLog.read().ifEmpty { "(diagnóstico vazio)" }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diagnóstico", text))
        Toast.makeText(this, "Diagnóstico copiado (${text.length} caracteres)", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginPanel(onLogin: (Marketplace) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(Marketplace.BR) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text("Entre com sua conta Amazon para registrar este aparelho como dispositivo Alexa.")
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "${selected.label} (${selected.domain})",
            onValueChange = {},
            readOnly = true,
            label = { Text("Loja da conta") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Marketplace.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text("${m.label} (${m.domain})") },
                    onClick = { selected = m; expanded = false },
                )
            }
        }
    }
    Button(onClick = { onLogin(selected) }) { Text("Entrar com Amazon") }
}

@Composable
private fun SessionPanel(s: Session, alexa: AlexaService, onLogout: () -> Unit) {
    val context = LocalContext.current
    val connection by alexa.connection.collectAsStateWithLifecycle()
    val dialog by alexa.dialog.collectAsStateWithLifecycle()
    val log by alexa.log.collectAsStateWithLifecycle()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) alexa.startListening()
    }

    Text("Conectado como ${s.customerName ?: "?"} (${s.marketplace.domain})")
    Text("Dispositivo: ${s.deviceName ?: s.deviceSerial}", style = MaterialTheme.typography.bodySmall)
    Text("AVS: ${connection.name} · ${dialog.name}")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            enabled = connection == AvsClient.State.Connected,
            onClick = {
                when {
                    dialog == AlexaService.Dialog.Listening -> alexa.stopListening()
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED -> alexa.startListening()
                    else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        ) { Text(if (dialog == AlexaService.Dialog.Listening) "Parar" else "Falar com a Alexa") }
        Button(onClick = { context.startActivity(Intent(context, ShowModeActivity::class.java)) }) { Text("Modo Show") }
        OutlinedButton(onClick = onLogout) { Text("Sair") }
    }
    WakeWordPanel()
    BackgroundPanel()
    LazyColumn(Modifier.fillMaxWidth()) {
        items(log.asReversed()) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
