package com.androidshow.alexa.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log de diagnóstico: vai para o logcat (tag "Diag") e para um arquivo interno que a tela inicial
 * copia. Tokens, cookies, chaves e códigos de autorização são mascarados antes de gravar.
 */
object DiagLog {
    private const val TAG = "Diag"
    private const val MAX_BYTES = 512 * 1024L
    private var file: File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "diag.log")
    }

    fun i(scope: String, message: String) = write("I", scope, message, null)
    fun w(scope: String, message: String, error: Throwable? = null) = write("W", scope, message, error)

    fun read(): String = file?.takeIf { it.exists() }?.readText().orEmpty()

    fun clear() {
        file?.delete()
    }

    @Synchronized
    private fun write(level: String, scope: String, message: String, error: Throwable?) {
        val text = redact(message) + (error?.let { "\n" + redact(Log.getStackTraceString(it)) } ?: "")
        // logcat corta linhas longas: divide em blocos.
        text.chunked(3500).forEach {
            if (level == "W") Log.w(TAG, "[$scope] $it") else Log.i(TAG, "[$scope] $it")
        }
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()))
            f.appendText("${time.format(Date())} $level [$scope] $text\n")
        }
    }

    private val SECRET_JSON = Regex(
        // O valor pode conter aspas escapadas (cookies x-acbbr/at-acbbr): consome \" até a aspa final.
        "\"(access_token|refresh_token|device_private_key|adp_token|authorization_code|code_verifier|source_token|Value|token)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\""
    )
    private val TOKEN_LITERAL = Regex("(Atz[a-z]\\||Atnr\\|)[^\\s\"&,\\\\]+")
    private val SECRET_PARAM = Regex(
        "((?:openid\\.oa2\\.authorization_code|source_token|code_verifier|access_token|refresh_token)=)[^&\\s\"]+"
    )
    private val BEARER = Regex("(Bearer\\s+)\\S+")

    fun redact(s: String): String = s
        .replace(SECRET_JSON) { "\"${it.groupValues[1]}\":\"***\"" }
        .replace(SECRET_PARAM) { "${it.groupValues[1]}***" }
        .replace(BEARER) { "${it.groupValues[1]}***" }
        .replace(TOKEN_LITERAL) { "${it.groupValues[1]}***" }
}
