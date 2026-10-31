package com.androidshow.alexa

import android.app.Application
import android.content.Context
import com.androidshow.alexa.auth.AuthApi
import com.androidshow.alexa.auth.AuthRepository
import com.androidshow.alexa.auth.SecureStore
import com.androidshow.alexa.avs.Attachments
import com.androidshow.alexa.avs.AvsClient
import com.androidshow.alexa.net.Http
import com.androidshow.alexa.show.RemoteImages
import com.androidshow.alexa.show.ShowModeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.alexa.start()
    }
}

/** Grafo de dependências manual: sem framework de DI, tudo criado uma vez e sob demanda. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = Http.client()
    val auth = AuthRepository(AuthApi(http), SecureStore(context))
    private val attachments = Attachments()
    val alexa = AlexaService(context, auth, AvsClient(http, auth, scope, attachments), attachments, scope)
    val show = ShowModeController(scope).also { show ->
        RemoteImages.http = http
        alexa.displayHandler = show::handle
        alexa.contextContributors += show::contextItems
    }
}

val Context.graph: AppGraph get() = (applicationContext as App).graph
