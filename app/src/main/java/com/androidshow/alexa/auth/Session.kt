package com.androidshow.alexa.auth

import kotlinx.serialization.Serializable

/** Credenciais do dispositivo registrado. Persistidas cifradas por [SecureStore]. */
@Serializable
data class Session(
    val marketplace: Marketplace,
    val deviceSerial: String,
    val deviceType: String,
    val refreshToken: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val adpToken: String? = null,
    val devicePrivateKey: String? = null,
    /** Cookies do site amazon.<domínio>, nome -> valor. Usados pelas APIs web da Alexa. */
    val cookies: Map<String, String> = emptyMap(),
    val customerId: String? = null,
    val customerName: String? = null,
    val deviceName: String? = null,
) {
    fun accessValid(now: Long = System.currentTimeMillis()) = accessExpiresAt - EXPIRY_MARGIN_MS > now
}

private const val EXPIRY_MARGIN_MS = 120_000L
