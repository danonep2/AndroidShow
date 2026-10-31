package com.androidshow.alexa

import android.app.Application
import android.content.Context
import com.androidshow.alexa.auth.AuthApi
import com.androidshow.alexa.auth.AuthRepository
import com.androidshow.alexa.auth.SecureStore
import com.androidshow.alexa.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Grafo de dependências manual: sem framework de DI, tudo criado uma vez e sob demanda. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http = Http.client()
    val auth = AuthRepository(AuthApi(http), SecureStore(context))
}

val Context.graph: AppGraph get() = (applicationContext as App).graph
