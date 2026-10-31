package com.androidshow.alexa.voice.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.androidshow.alexa.AlexaService
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.graph
import com.androidshow.alexa.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano (tipo microfone) que mantém o detector de palavra de ativação
 * ouvindo com a tela apagada ou o app em segundo plano. Só existe com a ativação por voz ligada.
 */
class WakeWordService : android.app.Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: OpenWakeWord? = null
    private var detector: WakeWordDetector? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = graph.wakeWord.config.value
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(config.label),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        if (detector == null) start(config)
        return START_STICKY
    }

    private fun start(config: WakeWordConfig) {
        val g = graph
        val e = runCatching { OpenWakeWord(this, config.model) }
            .onFailure { DiagLog.w("wake", "falha ao carregar ${config.model}", it) }
            .getOrNull() ?: return stopSelf()
        val d = WakeWordDetector(e, config) { preroll, _ -> g.alexa.onWakeWord(preroll, config) }
        engine = e
        detector = d
        g.microphone.add(d)
        scope.launch { g.alexa.dialog.collect { d.paused = it != AlexaService.Dialog.Idle } }
        DiagLog.i("wake", "ouvindo \"${config.label}\" (limiar ${config.threshold}, verificação na nuvem ${config.cloudVerified})")
    }

    override fun onDestroy() {
        scope.cancel()
        detector?.let { graph.microphone.remove(it) }
        // O quadro em processamento termina na thread do microfone; fecha o modelo logo depois.
        engine?.let { e -> Thread { Thread.sleep(200); e.close() }.start() }
        DiagLog.i("wake", "serviço parado")
        super.onDestroy()
    }

    private fun notification(label: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Palavra de ativação", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Ouvindo \"$label\"")
            .setContentText("Diga \"$label\" para falar com a Alexa")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "wakeword"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** Reinicia para aplicar uma configuração nova (outro modelo ou limiar). */
        fun restart(context: Context) {
            stop(context)
            start(context)
        }
    }
}
