package com.androidshow.alexa.avs

import com.androidshow.alexa.net.str
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectiveTest {

    @Test
    fun parsesSpeakDirective() {
        val d = Directive.parse(
            """{"directive":{"header":{"namespace":"SpeechSynthesizer","name":"Speak","messageId":"m1",
               "dialogRequestId":"d1"},"payload":{"url":"cid:abc","token":"t"}}}"""
        )!!
        assertEquals("SpeechSynthesizer.Speak", d.key)
        assertEquals("d1", d.dialogRequestId)
        assertEquals("cid:abc", d.payload.str("url"))
    }

    @Test
    fun rejectsNonDirectiveJson() {
        assertNull(Directive.parse("""{"event":{}}"""))
        assertNull(Directive.parse("not json"))
    }

    @Test
    fun extractsBoundaryFromAvsContentType() {
        // Cabeçalho real do AVS: o parâmetro type=application/json faz o OkHttp rejeitar o MediaType.
        assertEquals("------abcde123", boundaryOf("multipart/related; boundary=------abcde123; type=application/json"))
        assertEquals("abc", boundaryOf("multipart/form-data; boundary=\"abc\""))
        assertNull(boundaryOf("application/json"))
        assertNull(boundaryOf(""))
    }

    @Test
    fun attachmentArrivingAfterDirectiveIsDelivered() = runTest {
        val store = Attachments()
        val waiting = async { store.take("cid:abc") }
        store.put("<abc>", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), waiting.await())
    }
}
