package com.androidshow.alexa.show

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Toque num cartão da home multimodal.
 *
 * [Observado no UpdateData] Cada cartão traz em `data.actions[]` o evento pronto de cada parte
 * tocável (`viewId`: "media", "ctaButton", "progressiveRevealAction0"...), por exemplo:
 * `{"type":"SendEvent","data":{"namespace":"Alexa.Presentation.APL","name":"UserEvent",
 * "payload":{"presentationToken":"…Domain:Kitchen…","arguments":["homeCardDetails","{…}"]}}}`.
 * O SendEvent do documento só manda `[{itemMeta, entities}]` (o `interactionContext` do cartão),
 * que identifica o cartão; repassá-lo como UserEvent sem token dava HTTP 400.
 */
object HomeCardActions {

    /** Evento AVS (namespace, name, payload) do toque, ou null se o cartão não tiver um. */
    data class Event(val namespace: String, val name: String, val payload: JsonObject)

    fun eventFor(items: Collection<JsonObject>, args: JsonArray, sourceId: String?): Event? {
        val entities = (args.firstOrNull() as? JsonObject)?.get("entities") as? JsonArray ?: return null
        val card = items.firstOrNull { item ->
            val data = item["data"] as? JsonObject
            (data?.get("interactionContext") as? JsonObject)?.get("entities") == entities
        }?.get("data") as? JsonObject ?: return null
        val actions = (card["actions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { a -> a.sendEvent() != null }
        val chosen = actions.firstOrNull { it.str("viewId").equals(sourceId, ignoreCase = true) && sourceId != null }
            ?: actions.firstOrNull { it.str("viewId") in MAIN_VIEWS }
            ?: actions.firstOrNull()
            ?: return null
        val data = chosen.sendEvent() ?: return null
        return Event(
            namespace = data.str("namespace") ?: return null,
            name = data.str("name") ?: return null,
            payload = data["payload"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    private fun JsonObject.sendEvent(): JsonObject? =
        (this["deviceActions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("type") == "SendEvent" }?.get("data") as? JsonObject

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** Parte principal do cartão, quando o componente tocado não tem um viewId conhecido. */
    private val MAIN_VIEWS = setOf("media", "tv_image", "tv_title", "weather_forecast_container")
}
