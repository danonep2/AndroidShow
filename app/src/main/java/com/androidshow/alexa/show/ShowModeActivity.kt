package com.androidshow.alexa.show

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.androidshow.alexa.avs.AvsClient
import com.androidshow.alexa.ui.AppIcons
import com.androidshow.alexa.ui.Palette
import com.androidshow.alexa.ui.RoundButton
import com.androidshow.alexa.ui.VoiceOrb
import com.androidshow.alexa.ui.ios.Ios
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
        // Gesto de voltar (Android 13+ usa OnBackInvoked, que não passa por dispatchKeyEvent).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        val show = graph.show
        val alexa = graph.alexa
        setContent {
            MaterialTheme(colorScheme = Palette.colorScheme.copy(background = Color.Black, surface = Color.Black)) {
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
                                        Box(Modifier.fillMaxSize()) {
                                            // Atrás do APL: aparece na página 0 injetada, que é transparente.
                                            if (s.clock) Clock(weather = s.weather)
                                            AplDocument(s.document, { if (it == "payload") s.payload else null }, renderKey = s.generation) { show.onAplSendEvent("", it) }
                                        }
                                    } else {
                                        HomeScreen(s.cards, carousel = s.carousel, clock = s.clock, weather = s.weather)
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
                        // Volta à home do próprio modo Show. Fica no canto oposto ao do microfone.
                        RoundButton(
                            Icons.Filled.Home, "Home do modo Show", show::goHome,
                            Modifier.align(Alignment.TopEnd).padding(24.dp), background = BUTTON_BACKGROUND,
                        )
                        RoundButton(
                            AppIcons.Mic, "Falar com a Alexa", { toggleMic(alexa, dialog) },
                            Modifier.align(Alignment.BottomEnd).padding(24.dp), background = BUTTON_BACKGROUND,
                        )
                        VoiceOverlay(alexa, dialog, { toggleMic(alexa, dialog) }, Modifier.align(Alignment.BottomCenter))
                        Caption(
                            utterance.takeIf { dialog != AlexaService.Dialog.Idle },
                            Modifier.align(Alignment.BottomCenter).padding(bottom = ORB_RADIUS_SHOW + 28.dp),
                        )
                    }
                }
            }
        }
    }

    /**
     * O "voltar" é tratado aqui antes de qualquer view: a do APL recebe o foco e consumia a tecla,
     * deixando o usuário preso no modo Show.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) navigateBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Voltar: de um cartão/resposta para a home do modo Show; da home, sai do modo Show. */
    private fun navigateBack() {
        val show = graph.show
        if (show.isAwayFromHome) show.dismiss() else finish()
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

private val ORB_RADIUS_SHOW = 72.dp
private val BUTTON_BACKGROUND = Color(0x80000000)

/**
 * O mesmo círculo da tela inicial, fora da tela em repouso. Ao acionar a Alexa ele sobe pelo
 * centro da base e fica só a metade de cima à mostra, com a onda atrás.
 */
@Composable
private fun VoiceOverlay(alexa: AlexaService, dialog: AlexaService.Dialog, onTap: () -> Unit, modifier: Modifier) {
    val connection by alexa.connection.collectAsStateWithLifecycle()
    val micLevel by alexa.micLevel.collectAsStateWithLifecycle()
    val speechLevel by alexa.speechLevel.collectAsStateWithLifecycle()
    AnimatedVisibility(
        dialog != AlexaService.Dialog.Idle, modifier,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        Box(Modifier.fillMaxWidth().height(ORB_RADIUS_SHOW * 4)) {
            // Escurece o conteúdo atrás para o círculo e a onda se destacarem.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
            VoiceOrb(
                dialog, micLevel, speechLevel, connection == AvsClient.State.Connected,
                Modifier.matchParentSize(), radius = ORB_RADIUS_SHOW, centerY = 1f,
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(ORB_RADIUS_SHOW * 2, ORB_RADIUS_SHOW)
                    .clip(RoundedCornerShape(topStart = ORB_RADIUS_SHOW, topEnd = ORB_RADIUS_SHOW))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
            )
        }
    }
}

/** Legenda com a frase que a Alexa entendeu, logo acima do círculo. */
@Composable
private fun Caption(text: String?, modifier: Modifier) {
    AnimatedVisibility(text != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            "“${text.orEmpty()}”",
            color = Ios.Label,
            fontSize = 22.sp,
            modifier = Modifier
                .background(Ios.Group.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}
