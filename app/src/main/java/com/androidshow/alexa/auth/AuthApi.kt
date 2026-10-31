package com.androidshow.alexa.auth

import com.androidshow.alexa.net.Http
import com.androidshow.alexa.net.arr
import com.androidshow.alexa.net.awaitString
import com.androidshow.alexa.net.long
import com.androidshow.alexa.net.obj
import com.androidshow.alexa.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Serviço de identidade da Amazon (MAP/"panda"): registro do dispositivo, renovação do
 * access token e troca do refresh token por cookies do site.
 */
class AuthApi(private val http: OkHttpClient) {

    /** POST /auth/register: troca o authorization code por tokens de um dispositivo novo. */
    suspend fun register(m: Marketplace, id: DeviceIdentity, authorizationCode: String): Session {
        val body = buildJsonObject {
            putJsonArray("requested_extensions") { add("device_info"); add("customer_info") }
            putJsonObject("cookies") {
                putJsonArray("website_cookies") {}
                put("domain", ".${m.domain}")
            }
            putJsonObject("registration_data") {
                put("domain", "Device")
                put("app_version", DeviceProfile.APP_VERSION)
                put("device_type", DeviceProfile.DEVICE_TYPE)
                put("device_name", DeviceProfile.DEVICE_NAME)
                put("os_version", DeviceProfile.OS_VERSION)
                put("device_serial", id.serial)
                put("device_model", DeviceProfile.DEVICE_MODEL)
                put("app_name", DeviceProfile.APP_NAME)
                put("software_version", DeviceProfile.SOFTWARE_VERSION)
            }
            putJsonObject("auth_data") {
                put("use_global_authentication", "true")
                put("client_id", id.clientId)
                put("authorization_code", authorizationCode)
                put("code_verifier", id.codeVerifier)
                put("code_algorithm", "SHA-256")
                put("client_domain", "DeviceLegacy")
            }
            putJsonArray("requested_token_type") { add("bearer"); add("mac_dms"); add("website_cookies") }
        }
        val request = Request.Builder()
            .url("https://${m.apiHost}/auth/register")
            .header("x-amzn-identity-auth-domain", m.apiHost)
            .header("Accept-Language", m.locale)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        val json = parse(http.newCall(request).awaitString())
        val success = json.obj("response")?.obj("success")
            ?: throw AuthException("Registro recusado: ${json.obj("response")?.obj("error") ?: json}")
        val tokens = success.obj("tokens") ?: throw AuthException("Resposta sem tokens")
        val bearer = tokens.obj("bearer") ?: throw AuthException("Resposta sem bearer")
        val macDms = tokens.obj("mac_dms")
        val customer = success.obj("extensions")?.obj("customer_info")
        val device = success.obj("extensions")?.obj("device_info")
        val cookies = tokens.arr("website_cookies").orEmpty().associate {
            val c = it.jsonObject
            c.str("Name").orEmpty() to c.str("Value").orEmpty()
        }
        return Session(
            marketplace = m,
            deviceSerial = device?.str("device_serial_number") ?: id.serial,
            deviceType = device?.str("device_type") ?: DeviceProfile.DEVICE_TYPE,
            refreshToken = bearer.str("refresh_token") ?: throw AuthException("Sem refresh_token"),
            accessToken = bearer.str("access_token") ?: throw AuthException("Sem access_token"),
            accessExpiresAt = expiry(bearer.long("expires_in")),
            adpToken = macDms?.str("adp_token"),
            devicePrivateKey = macDms?.str("device_private_key"),
            cookies = cookies,
            customerId = success.str("customer_id") ?: customer?.str("user_id"),
            customerName = customer?.str("given_name") ?: customer?.str("name"),
            deviceName = device?.str("device_name"),
        )
    }

    /** POST /auth/token: novo access token a partir do refresh token. */
    suspend fun refresh(s: Session): Session {
        val m = s.marketplace
        val form = FormBody.Builder()
            .add("app_name", DeviceProfile.APP_NAME)
            .add("app_version", DeviceProfile.APP_VERSION)
            .add("di.sdk.version", DeviceProfile.SDK_VERSION)
            .add("source_token", s.refreshToken)
            .add("source_token_type", "refresh_token")
            .add("requested_token_type", "access_token")
            .add("di.hw.version", DeviceProfile.DEVICE_MODEL)
            .add("di.os.name", DeviceProfile.OS_NAME)
            .add("di.os.version", DeviceProfile.OS_VERSION)
            .add("current_version", DeviceProfile.SDK_VERSION)
            .build()
        val request = Request.Builder()
            .url("https://${m.apiHost}/auth/token")
            .header("x-amzn-identity-auth-domain", m.apiHost)
            .post(form)
            .build()
        val json = parse(http.newCall(request).awaitString())
        val token = json.str("access_token") ?: throw AuthException("Renovação sem access_token: $json")
        return s.copy(accessToken = token, accessExpiresAt = expiry(json.long("expires_in")))
    }

    /** POST /ap/exchangetoken/cookies: cookies de sessão do site, para as APIs de alexa.<domínio>. */
    suspend fun exchangeCookies(s: Session): Map<String, String> {
        val m = s.marketplace
        val form = FormBody.Builder()
            .add("app_name", DeviceProfile.APP_NAME)
            .add("requested_token_type", "auth_cookies")
            .add("domain", m.wwwHost)
            .add("source_token_type", "refresh_token")
            .add("source_token", s.refreshToken)
            .build()
        val request = Request.Builder()
            .url("https://${m.wwwHost}/ap/exchangetoken/cookies")
            .header("x-amzn-identity-auth-domain", m.apiHost)
            .post(form)
            .build()
        val json = parse(http.newCall(request).awaitString())
        val byDomain = json.obj("response")?.obj("tokens")?.obj("cookies")
            ?: throw AuthException("Resposta sem cookies")
        return byDomain.values.flatMap { list ->
            (list as? JsonArray).orEmpty().map { it.jsonObject }
        }.associate { it.str("Name").orEmpty() to it.str("Value").orEmpty() }
    }

    private fun parse(text: String): JsonObject = Http.json.parseToJsonElement(text).jsonObject

    private fun expiry(seconds: Long?) = System.currentTimeMillis() + (seconds ?: 3600L) * 1000
}

class AuthException(message: String) : Exception(message)
