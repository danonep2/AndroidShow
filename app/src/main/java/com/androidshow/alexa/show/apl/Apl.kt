package com.androidshow.alexa.show.apl

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.amazon.apl.android.APLController
import com.amazon.apl.android.APLJSONData
import com.amazon.apl.android.APLLayout
import com.amazon.apl.android.APLOptions
import com.amazon.apl.android.Content
import com.amazon.apl.android.IAPLController
import com.amazon.apl.android.RootConfig
import com.amazon.apl.android.dependencies.IPackageLoader
import com.amazon.apl.android.scaling.Scaling
import com.amazon.apl.enums.ViewportMode
import com.androidshow.alexa.R
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.net.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Renderizador APL oficial da Amazon (apl-viewhost-android 2024.3, motor apl-core em C++),
 * compilado localmente para arm64 (ver docs/APL.md).
 */
object Apl {
    @Volatile var ready = false
        private set

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var http: OkHttpClient
    private val packages = ConcurrentHashMap<String, String>()

    /**
     * Viewports de Echo Show em paisagem (dp). O celular em paisagem tem ~853×384 dp, abaixo do
     * menor hub (960×480): o motor escolhe o viewport de hub mais próximo da proporção da tela,
     * monta o documento nele e reduz a escala para caber, como num aparelho oficial.
     */
    val hubScaling by lazy {
        fun spec(minW: Int, maxW: Int, minH: Int, maxH: Int) = Scaling.ViewportSpecification.builder()
            .minWidth(minW).maxWidth(maxW).minHeight(minH).maxHeight(maxH)
            .round(false).mode(ViewportMode.kViewportModeHub).build()
        Scaling(
            10.0,
            // Só o perfil hubLandscapeSmall (Echo Show 5, 480 dp de altura): é o mais próximo da
            // proporção do celular deitado (~2,2:1) e os layouts da home têm tamanhos para ele.
            listOf(spec(1280, 1919, 800, 800)),
            listOf(ViewportMode.kViewportModeHub),
        )
    }

    /** Carrega a biblioteca nativa em segundo plano (algumas centenas de ms na primeira vez). */
    fun init(context: Context, client: OkHttpClient) {
        http = client
        Thread({
            runCatching {
                APLController.initializeAPL(context.applicationContext)
                ready = APLController.waitForInitializeAPLToComplete(null)
                DiagLog.i("apl", "renderizador pronto: $ready")
            }.onFailure { DiagLog.w("apl", "falha ao iniciar o renderizador", it) }
        }, "apl-init").start()
    }

    /**
     * Pacotes importados pelos documentos: a URL `source` quando vem no import (templates da home)
     * ou a CDN pública de pacotes APL da Amazon (alexa-layouts, alexa-styles...). Cache em memória.
     */
    val packageLoader = IPackageLoader { request, success, failure ->
        val url = request.source?.takeIf { it.isNotBlank() }
            ?: "https://arl.assets.apl-alexa.com/packages/${request.packageName}/${request.version}/document.json"
        packages[url]?.let {
            success.onSuccess(request, APLJSONData.create(it))
            return@IPackageLoader
        }
        io.launch {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).await().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    r.body!!.string()
                }
            }.onSuccess {
                packages[url] = it
                success.onSuccess(request, APLJSONData.create(it))
            }.onFailure {
                DiagLog.w("apl", "pacote ${request.packageName} ${request.version} falhou: ${it.message}")
                failure.onFailure(request, it.message.orEmpty())
            }
        }
    }
}

/**
 * Desenha um documento APL. [data] responde aos parâmetros do mainTemplate (nome → JSON);
 * [onSendEvent] recebe os argumentos dos comandos SendEvent do documento.
 */
@Composable
fun AplDocument(
    document: String,
    data: (String) -> String?,
    modifier: Modifier = Modifier,
    /** Recria a view (e o documento) quando muda, mesmo com o mesmo documento. */
    renderKey: Any = Unit,
    /** Argumentos do SendEvent e o componente que o disparou (`sources`: id, type, handler...). */
    onSendEvent: (Array<Any?>, Map<String, Any?>) -> Unit = { _, _ -> },
) {
    // Um controlador por documento: key() recria a view (e o render) só quando o documento muda.
    key(document, renderKey) {
        val holder = remember { arrayOfNulls<IAPLController>(1) }
        AndroidView(
            // Sem tamanho explícito o AndroidView mede o conteúdo e o APLLayout fica 0×0 (tela preta).
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                val layout = LayoutInflater.from(ctx).inflate(R.layout.apl_layout, null) as APLLayout
                layout.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                layout.setScaling(Apl.hubScaling)
                layout.addMetricsReadyListener { m ->
                    DiagLog.i("apl", "viewport ${m.width()}x${m.height()} px, dpi ${m.dpi()}, modo ${m.mode()}, " +
                        "${m.width() * 160 / m.dpi().coerceAtLeast(1)}x${m.height() * 160 / m.dpi().coerceAtLeast(1)} dp")
                    runCatching {
                        val t = com.amazon.apl.android.scaling.MetricsTransform.create(m)
                        val sm = t.scaledMetrics
                        DiagLog.i("apl", "escalado: ${sm.width()}x${sm.height()} px dpi ${sm.dpi()} → viewhost ${t.scaledViewhostWidth}x${t.scaledViewhostHeight}")
                    }.onFailure { DiagLog.w("apl", "transform", it) }
                }
                val options = APLOptions.builder()
                    .packageLoader(Apl.packageLoader)
                    .contentDataRetriever { request, success, failure ->
                        val json = data(request)
                        if (json != null) success.onSuccess(request, json) else failure.onFailure(request, "sem dados para $request")
                    }
                    .sendEventCallbackV2 { args, _, sources, _ -> onSendEvent(args, sources.orEmpty()) }
                    .build()
                holder[0] = runCatching {
                    APLController.builder()
                        .aplDocument(document)
                        .aplOptions(options)
                        .rootConfig(RootConfig.create(ctx).agent("AndroidShow", "1.0"))
                        .aplLayout(layout)
                        .errorCallback { DiagLog.w("apl", "erro ao desenhar documento", it) }
                        .render()
                }.onFailure { DiagLog.w("apl", "render falhou", it) }.getOrNull()
                layout
            },
        )
        DisposableEffect(Unit) {
            onDispose {
                holder[0]?.finishDocument()
                holder[0] = null
            }
        }
    }
}
