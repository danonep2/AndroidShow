package com.androidshow.alexa.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRequestTest {

    @Test
    fun clientIdEncodesSerialAndDeviceType() {
        val id = DeviceIdentity.generate()
        val decoded = id.clientId.chunked(2).map { it.toInt(16).toByte() }.toByteArray().decodeToString()
        assertEquals("${id.serial}#${DeviceProfile.DEVICE_TYPE}", decoded)
        assertEquals(32, id.serial.length)
    }

    @Test
    fun pkceChallengeMatchesRfc7636Example() {
        val id = DeviceIdentity("S", "C", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", id.codeChallenge)
    }

    @Test
    fun signInUrlUsesMarketplaceHandle() {
        val url = LoginRequest.signInUrl(Marketplace.BR, DeviceIdentity.generate()).toHttpUrl()
        assertEquals("www.amazon.com.br", url.host)
        assertEquals("amzn_dp_project_dee_ios_br", url.queryParameter("openid.assoc_handle"))
        assertTrue(url.queryParameter("openid.oa2.client_id")!!.startsWith("device:"))
        assertEquals("amzn_dp_project_dee_ios", Marketplace.US.assocHandle)
    }

    @Test
    fun extractsAuthorizationCodeOnlyFromMapLanding() {
        assertEquals(
            "ANabc",
            LoginRequest.authorizationCode("https://www.amazon.com.br/ap/maplanding?openid.oa2.authorization_code=ANabc"),
        )
        assertNull(LoginRequest.authorizationCode("https://www.amazon.com.br/ap/signin?openid.oa2.authorization_code=x"))
    }
}
