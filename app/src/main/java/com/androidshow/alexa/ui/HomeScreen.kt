package com.androidshow.alexa.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidshow.alexa.AlexaService
import com.androidshow.alexa.avs.AvsClient

/** Tela inicial limpa: só o círculo e a onda. Tocar no círculo fala com a Alexa. */
@Composable
fun HomeScreen(alexa: AlexaService, onSettings: () -> Unit) {
    val context = LocalContext.current
    val connection by alexa.connection.collectAsStateWithLifecycle()
    val dialog by alexa.dialog.collectAsStateWithLifecycle()
    val micLevel by alexa.micLevel.collectAsStateWithLifecycle()
    val speechLevel by alexa.speechLevel.collectAsStateWithLifecycle()
    val connected = connection == AvsClient.State.Connected
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) alexa.startListening()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background)) {
        VoiceOrb(dialog, micLevel, speechLevel, connected, Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.Center)
                .size(minOf(maxWidth, maxHeight) * ORB_RADIUS * 2)
                .clip(CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = connected) {
                    when {
                        dialog == AlexaService.Dialog.Listening -> alexa.stopListening()
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED -> alexa.startListening()
                        else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
        )
        IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = Color.White.copy(alpha = 0.45f))
        }
    }
}
