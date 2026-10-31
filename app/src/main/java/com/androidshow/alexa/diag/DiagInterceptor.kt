package com.androidshow.alexa.diag

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Registra pedidos e respostas dos serviços de identidade e do site da Amazon (corpo mascarado).
 * Ignora o AVS e imagens: seus corpos são streams longos ou binários.
 */
class DiagInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val logged = host.startsWith("api.amazon.") || host.startsWith("www.amazon.") || host.startsWith("alexa.amazon.")
        if (!logged) return chain.proceed(request)

        val body = request.body?.let { b ->
            if (b.isDuplex() || b.isOneShot()) "<stream>" else Buffer().also(b::writeTo).readUtf8().take(4000)
        }
        DiagLog.i("http", "→ ${request.method} ${request.url}\n${body.orEmpty()}")
        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            DiagLog.w("http", "✗ ${request.method} ${request.url}", e)
            throw e
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        val peek = runCatching { response.peekBody(16_384).string() }.getOrDefault("<sem corpo>")
        DiagLog.i("http", "← ${response.code} ${request.url.encodedPath} (${ms} ms)\n$peek")
        return response
    }
}
