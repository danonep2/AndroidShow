package com.androidshow.alexa.avs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Anexos binários (áudio da fala) que chegam em partes multipart separadas da diretiva.
 * A diretiva pode ser processada antes do anexo terminar de chegar, então quem consome espera.
 */
class Attachments {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    fun put(contentId: String, bytes: ByteArray) {
        slot(normalize(contentId)).complete(bytes)
    }

    /** Aguarda o anexo de `cid:<id>` (ou `<id>`) e o remove do armazenamento. */
    suspend fun take(reference: String, timeoutMs: Long = 15_000): ByteArray? {
        val id = normalize(reference)
        val bytes = withTimeoutOrNull(timeoutMs) { slot(id).await() }
        pending.remove(id)
        return bytes
    }

    private fun slot(id: String) = pending.getOrPut(id) { CompletableDeferred() }

    private fun normalize(id: String) = id.removePrefix("cid:").trim().removePrefix("<").removeSuffix(">")
}
