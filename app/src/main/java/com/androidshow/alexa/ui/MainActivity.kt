package com.androidshow.alexa.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidshow.alexa.auth.LoginActivity
import com.androidshow.alexa.auth.Marketplace
import com.androidshow.alexa.auth.Session
import com.androidshow.alexa.graph

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
                            SessionPanel(s, onLogout = graph.auth::logout)
                        }
                    }
                }
            }
        }
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
private fun SessionPanel(s: Session, onLogout: () -> Unit) {
    Text("Conectado como ${s.customerName ?: "?"} (${s.marketplace.domain})")
    Text("Dispositivo: ${s.deviceName ?: s.deviceSerial}", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onLogout) { Text("Sair") }
}
