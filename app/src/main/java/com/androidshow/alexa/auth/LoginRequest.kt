package com.androidshow.alexa.auth

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Dados gerados por instalação antes do login: serial do "dispositivo" e o par PKCE. */
@Serializable
data class DeviceIdentity(val serial: String, val clientId: String, val codeVerifier: String) {

    val codeChallenge: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
            .base64Url()

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): DeviceIdentity {
            val serial = ByteArray(16).also(random::nextBytes).toHex().uppercase()
            // client_id = hex("<serial>#<deviceType>"): é assim que a Amazon associa o login ao tipo de dispositivo.
            val clientId = "$serial#${DeviceProfile.DEVICE_TYPE}".toByteArray().toHex()
            val verifier = ByteArray(32).also(random::nextBytes).base64Url()
            return DeviceIdentity(serial, clientId, verifier)
        }
    }
}

/** URL do login OpenID/OAuth da Amazon e leitura do código no redirecionamento /ap/maplanding. */
object LoginRequest {
    private const val ID_SELECT = "http://specs.openid.net/auth/2.0/identifier_select"

    fun signInUrl(m: Marketplace, id: DeviceIdentity): String {
        val base = "https://${m.wwwHost}"
        return "$base/ap/signin".toHttpUrl().newBuilder()
            .addQueryParameter("openid.return_to", "$base/ap/maplanding")
            .addQueryParameter("openid.assoc_handle", m.assocHandle)
            .addQueryParameter("openid.identity", ID_SELECT)
            .addQueryParameter("pageId", m.assocHandle)
            .addQueryParameter("accountStatusPolicy", "P1")
            .addQueryParameter("openid.claimed_id", ID_SELECT)
            .addQueryParameter("openid.mode", "checkid_setup")
            .addQueryParameter("openid.ns.oa2", "http://www.amazon.com/ap/ext/oauth/2")
            .addQueryParameter("openid.oa2.client_id", "device:${id.clientId}")
            .addQueryParameter("openid.ns.pape", "http://specs.openid.net/extensions/pape/1.0")
            .addQueryParameter("openid.oa2.response_type", "code")
            .addQueryParameter("openid.ns", "http://specs.openid.net/auth/2.0")
            .addQueryParameter("openid.pape.max_auth_age", "0")
            .addQueryParameter("openid.oa2.scope", "device_auth_access")
            .addQueryParameter("openid.oa2.code_challenge_method", "S256")
            .addQueryParameter("openid.oa2.code_challenge", id.codeChallenge)
            .addQueryParameter("language", m.language)
            .build()
            .toString()
    }

    /** Devolve o authorization code se [url] for o redirecionamento final do login. */
    fun authorizationCode(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!parsed.encodedPath.endsWith("/ap/maplanding")) return null
        return parsed.queryParameter("openid.oa2.authorization_code")
    }
}

internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

internal fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private val HEX = "0123456789abcdef".toCharArray()
