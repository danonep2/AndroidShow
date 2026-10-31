package com.androidshow.alexa.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagLogTest {

    @Test
    fun masksSecretsInJsonFormAndUrls() {
        val json = """{"access_token":"Atza|abc","refresh_token" : "Atnr|xyz","Name":"session-id","Value":"123"}"""
        val masked = DiagLog.redact(json)
        assertFalse(masked.contains("Atza") || masked.contains("Atnr") || masked.contains("123"))
        assertEquals(true, masked.contains("session-id"))

        assertEquals(
            "https://x/ap/maplanding?openid.oa2.authorization_code=***&a=1",
            DiagLog.redact("https://x/ap/maplanding?openid.oa2.authorization_code=ANxyz&a=1"),
        )
        assertEquals("source_token=***&app_name=x", DiagLog.redact("source_token=Atnr|q&app_name=x"))
        assertEquals("Authorization: Bearer ***", DiagLog.redact("Authorization: Bearer Atza|q"))
    }
}
