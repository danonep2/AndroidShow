package com.androidshow.alexa.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidshow.alexa.BuildConfig
import com.androidshow.alexa.auth.LoginActivity
import com.androidshow.alexa.auth.Marketplace
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.graph
import com.androidshow.alexa.show.ShowModeActivity
import com.androidshow.alexa.voice.wake.WakeWordService

/** Telas do app; [depth] decide o sentido do deslize (empilhar entra pela direita). */
private enum class Page(val depth: Int) { Home(0), Login(0), Settings(1), Marketplace(1), VoiceActivation(2), ShowMode(2), ShowCategories(3), History(2), Devices(1), DeviceDetail(2) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = Palette.colorScheme) {
                Surface(Modifier.fillMaxSize()) {
                    val session by graph.auth.session.collectAsStateWithLifecycle()
                    var stack by rememberSaveable { mutableStateOf(debugStartPages()) }
                    var marketplace by rememberSaveable { mutableStateOf(Marketplace.BR) }
                    var deviceId by rememberSaveable { mutableStateOf(if (BuildConfig.DEBUG) intent.getStringExtra("device") else null) }
                    val loggedIn = session != null
                    // Entrar ou sair volta para a raiz; girar a tela mantém a página.
                    var lastLoggedIn by rememberSaveable { mutableStateOf(loggedIn) }
                    LaunchedEffect(loggedIn) {
                        if (loggedIn != lastLoggedIn) {
                            lastLoggedIn = loggedIn
                            stack = emptyList()
                        }
                    }

                    fun push(p: Page) { stack = stack + p }
                    fun pop() { stack = stack.dropLast(1) }
                    BackHandler(enabled = stack.isNotEmpty(), onBack = ::pop)

                    val page = stack.lastOrNull() ?: if (loggedIn) Page.Home else Page.Login
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            val forward = targetState.depth > initialState.depth
                            val duration = tween<androidx.compose.ui.unit.IntOffset>(320)
                            if (forward) {
                                slideInHorizontally(duration) { it } togetherWith slideOutHorizontally(duration) { -it / 3 }
                            } else {
                                slideInHorizontally(duration) { -it / 3 } togetherWith slideOutHorizontally(duration) { it }
                            }
                        },
                        label = "navegação",
                    ) { p ->
                        val s = session
                        when (p) {
                            Page.Home -> HomeScreen(
                                graph.alexa,
                                onSettings = { push(Page.Settings) },
                                onShowMode = { startActivity(Intent(this@MainActivity, ShowModeActivity::class.java)) },
                                onDevices = { push(Page.Devices) },
                            )
                            Page.Devices -> DevicesScreen(graph.devices, onBack = ::pop, onOpen = { deviceId = it.id; push(Page.DeviceDetail) })
                            Page.DeviceDetail -> DeviceDetailScreen(graph.devices, deviceId.orEmpty(), onBack = ::pop)
                            Page.Settings -> if (s != null) SettingsScreen(
                                s, graph.alexa,
                                onBack = ::pop,
                                onVoiceActivation = { push(Page.VoiceActivation) },
                                onShowMode = { push(Page.ShowMode) },
                                onHistory = { push(Page.History) },
                                onCopyDiagnostics = ::copyDiagnostics,
                                onLogout = graph.auth::logout,
                            )
                            Page.VoiceActivation -> VoiceActivationScreen(onBack = ::pop)
                            Page.ShowMode -> ShowModeSettingsScreen(onBack = ::pop, onCategories = { push(Page.ShowCategories) })
                            Page.ShowCategories -> ShowCategoriesScreen(onBack = ::pop)
                            Page.History -> HistoryScreen(graph.alexa, onBack = ::pop)
                            Page.Login -> LoginScreen(
                                marketplace,
                                onPickMarketplace = { push(Page.Marketplace) },
                                onLogin = { startActivity(LoginActivity.intent(this@MainActivity, marketplace)) },
                                onCopyDiagnostics = ::copyDiagnostics,
                            )
                            Page.Marketplace -> MarketplaceScreen(marketplace, onSelect = { marketplace = it; pop() }, onBack = ::pop)
                        }
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

    /** Só em debug: `am start ... --es pages Settings,VoiceActivation` abre direto numa página (o HyperOS bloqueia `input tap`). */
    private fun debugStartPages(): List<Page> =
        if (!BuildConfig.DEBUG) emptyList()
        else intent.getStringExtra("pages")?.split(',')?.mapNotNull { n -> Page.entries.find { it.name == n.trim() } }.orEmpty()

    private fun copyDiagnostics() {
        val text = DiagLog.read().ifEmpty { "(diagnóstico vazio)" }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diagnóstico", text))
        Toast.makeText(this, "Diagnóstico copiado (${text.length} caracteres)", Toast.LENGTH_SHORT).show()
    }
}
