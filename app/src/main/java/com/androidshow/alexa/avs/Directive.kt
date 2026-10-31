package com.androidshow.alexa.avs

import com.androidshow.alexa.net.Http
import com.androidshow.alexa.net.obj
import com.androidshow.alexa.net.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/** Diretiva recebida do AVS. O payload fica como JSON cru: cada handler lê só o que precisa. */
data class Directive(
    val namespace: String,
    val name: String,
    val messageId: String,
    val dialogRequestId: String?,
    val payload: JsonObject,
) {
    val key get() = "$namespace.$name"

    companion object {
        fun parse(text: String): Directive? {
            val root = runCatching { Http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val directive = root.obj("directive") ?: return null
            val header = directive.obj("header") ?: return null
            return Directive(
                namespace = header.str("namespace") ?: return null,
                name = header.str("name") ?: return null,
                messageId = header.str("messageId").orEmpty(),
                dialogRequestId = header.str("dialogRequestId"),
                payload = directive.obj("payload") ?: JsonObject(emptyMap()),
            )
        }
    }
}

/** Monta o bloco `event` de um evento AVS. */
fun avsEvent(
    namespace: String,
    name: String,
    payload: JsonObject = JsonObject(emptyMap()),
    dialogRequestId: String? = null,
): JsonObject = buildJsonObject {
    putJsonObject("header") {
        put("namespace", namespace)
        put("name", name)
        put("messageId", UUID.randomUUID().toString())
        dialogRequestId?.let { put("dialogRequestId", it) }
    }
    put("payload", payload)
}

/** Monta um item de contexto (`{header:{namespace,name}, payload}`). */
fun avsState(namespace: String, name: String, payload: JsonObject): JsonObject = buildJsonObject {
    putJsonObject("header") {
        put("namespace", namespace)
        put("name", name)
    }
    put("payload", payload)
}
