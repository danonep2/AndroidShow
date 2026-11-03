package com.androidshow.alexa.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.androidshow.alexa.diag.DiagLog
import com.androidshow.alexa.graph
import com.androidshow.alexa.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Login na página oficial da Amazon dentro de um WebView. Ao chegar em /ap/maplanding,
 * captura o authorization code e registra o dispositivo (sem ver a senha do usuário).
 */
class LoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var marketplace: Marketplace
    private lateinit var identity: DeviceIdentity
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private var finishing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceProfile.isConfigured) {
            DiagLog.w("login", "tipo de dispositivo não configurado neste build")
            Toast.makeText(this, "Este build não tem tipo de dispositivo configurado (veja o README).", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        marketplace = Marketplace.valueOf(intent.getStringExtra(EXTRA_MARKETPLACE) ?: Marketplace.BR.name)
        identity = savedInstanceState?.getString(STATE_IDENTITY)
            ?.let { Http.json.decodeFromString(DeviceIdentity.serializer(), it) }
            ?: DeviceIdentity.generate()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = Client()
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        setContentView(FrameLayout(this).apply {
            addView(web)
            addView(progress, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        })

        if (savedInstanceState == null) {
            CookieManager.getInstance().removeAllCookies(null)
            val url = LoginRequest.signInUrl(marketplace, identity)
            DiagLog.i("login", "início: ${marketplace.name}, deviceType ${DeviceProfile.DEVICE_TYPE}\n$url")
            web.loadUrl(url)
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_IDENTITY, Http.json.encodeToString(DeviceIdentity.serializer(), identity))
        web.saveState(outState)
    }

    @Deprecated("Activity puro; sem OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    private fun onAuthorizationCode(code: String) {
        if (finishing) return
        finishing = true
        web.stopLoading()
        web.visibility = View.INVISIBLE
        progress.visibility = View.VISIBLE
        DiagLog.i("login", "authorization code recebido; registrando dispositivo (${marketplace.name}, ${marketplace.apiHost})")
        scope.launch {
            runCatching { graph.auth.completeLogin(marketplace, identity, code) }
                .onSuccess {
                    DiagLog.i("login", "registro concluído: ${it.deviceName} / ${it.deviceType}")
                    CookieManager.getInstance().removeAllCookies(null)
                    setResult(RESULT_OK)
                    finish()
                }
                .onFailure {
                    DiagLog.w("login", "falha no registro", it)
                    Toast.makeText(this@LoginActivity, "Falha no registro: ${it.message}", Toast.LENGTH_LONG).show()
                    setResult(RESULT_CANCELED)
                    finish()
                }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            DiagLog.i("login", "navegar${if (request.isRedirect) " (redirect)" else ""}: ${request.url}")
            val code = LoginRequest.authorizationCode(request.url.toString()) ?: return false
            onAuthorizationCode(code)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            DiagLog.i("login", "página iniciada: $url")
            LoginRequest.authorizationCode(url)?.let(::onAuthorizationCode)
        }

        override fun onPageFinished(view: WebView, url: String) {
            DiagLog.i("login", "página carregada: $url")
            // Captura mensagens de erro visíveis da Amazon; a página traz vários alertas ocultos de reserva.
            view.evaluateJavascript(
                "(function(){var e=document.querySelectorAll('.a-alert-content,#auth-error-message-box,#auth-warning-message-box');" +
                    "return Array.prototype.filter.call(e,function(x){return x.offsetParent!==null})" +
                    ".map(function(x){return x.innerText.trim()}).filter(Boolean).join(' | ')})()"
            ) { text ->
                if (text != null && text != "\"\"" && text != "null") DiagLog.w("login", "mensagem na página: $text")
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) DiagLog.w("login", "erro ${error.errorCode} '${error.description}' em ${request.url}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) DiagLog.w("login", "HTTP ${response.statusCode} em ${request.url}")
        }
    }

    companion object {
        private const val EXTRA_MARKETPLACE = "marketplace"
        private const val STATE_IDENTITY = "identity"

        fun intent(context: Context, m: Marketplace) =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_MARKETPLACE, m.name)
    }
}
