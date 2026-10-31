package com.androidshow.alexa.show

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidshow.alexa.AlexaService
import com.androidshow.alexa.graph
import com.androidshow.alexa.show.apl.Apl
import com.androidshow.alexa.show.apl.AplDocument
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Modo Show: tela cheia que fica ligada (como um Echo Show), com relógio em repouso e o
 * conteúdo visual da Alexa (cartões, player, APL) quando houver.
 */
class ShowModeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val show = graph.show
        val alexa = graph.alexa
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black)) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    val screen by show.screen.collectAsStateWithLifecycle()
                    val dialog by alexa.dialog.collectAsStateWithLifecycle()
                    val utterance by alexa.utterance.collectAsStateWithLifecycle()
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { show.dismiss() },
                    ) {
                        Crossfade(screen, label = "screen") { s ->
                            when (s) {
                                is ShowScreen.Home ->
                                    if (Apl.ready && s.document != null && s.payload != null) {
                                        AplDocument(s.document, { if (it == "payload") s.payload else null }) { show.onAplSendEvent("", it) }
                                    } else {
                                        HomeScreen(s.cards)
                                    }
                                is ShowScreen.Card -> TemplateCard(s.template)
                                is ShowScreen.Player -> PlayerCard(s.info)
                                is ShowScreen.Apl ->
                                    if (Apl.ready) {
                                        AplDocument(s.document.toString(), { name -> s.dataFor(name) }) { show.onAplSendEvent(s.token, it) }
                                    } else {
                                        AplFallback(s)
                                    }
                            }
                        }
                        FloatingActionButton(
                            onClick = { toggleMic(alexa, dialog) },
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                        ) { Text(if (dialog == AlexaService.Dialog.Listening) "■" else "🎤") }
                        Caption(utterance.takeIf { dialog != AlexaService.Dialog.Idle }, Modifier.align(Alignment.BottomCenter))
                        DialogBar(dialog, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.show.setVisible(true)
    }

    override fun onStop() {
        graph.show.setVisible(false)
        super.onStop()
    }

    private fun toggleMic(alexa: AlexaService, dialog: AlexaService.Dialog) {
        if (dialog == AlexaService.Dialog.Listening) {
            alexa.stopListening()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            alexa.startListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }
}

/** Barra de luz no rodapé, como o anel do Echo: azul ouvindo, ciano pensando/falando. */
@Composable
private fun DialogBar(dialog: AlexaService.Dialog, modifier: Modifier) {
    val colors = when (dialog) {
        AlexaService.Dialog.Listening -> listOf(Color(0xFF0050FF), Color(0xFF00CAFF), Color(0xFF0050FF))
        AlexaService.Dialog.Thinking, AlexaService.Dialog.Speaking -> listOf(Color(0xFF00CAFF), Color(0xFF00E5FF), Color(0xFF00CAFF))
        AlexaService.Dialog.Idle -> null
    }
    AnimatedVisibility(colors != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.horizontalGradient(colors ?: listOf(Color.Transparent, Color.Transparent))))
    }
}

/** Legenda com a frase que a Alexa entendeu, como no Echo Show. */
@Composable
private fun Caption(text: String?, modifier: Modifier) {
    AnimatedVisibility(text != null, modifier.padding(bottom = 32.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(
            "“${text.orEmpty()}”",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFFE6EDF5),
            modifier = Modifier
                .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}
