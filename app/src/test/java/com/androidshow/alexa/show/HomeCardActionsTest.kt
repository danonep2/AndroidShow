package com.androidshow.alexa.show

import com.androidshow.alexa.net.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Formato observado no UpdateData da home (cartão "Receitas populares", 2026-11-08). */
class HomeCardActionsTest {
    private fun obj(s: String) = Http.json.parseToJsonElement(s) as JsonObject

    private fun action(view: String, ingress: String) =
        """{"name":"onClickExpand","viewId":"$view","deviceActions":[{"type":"SendEvent","data":{"name":"UserEvent","namespace":"Alexa.Presentation.APL",
        "payload":{"presentationToken":"amzn1.as-tt.v1.Domain:Kitchen#TID#Domain:Kitchen PresentationToken","arguments":["homeCardDetails","{\"cardIngressPoint\":\"$ingress\"}"]}},"blocking":true}]}"""

    private val recipeCard = obj(
        """{"id":"pull://abc/1","data":{"namespace":"GenericMedia","data":{"card_title":"Receitas populares"},
        "interactionContext":{"entities":[{"entity":"amzn1.alexa.kitchen.document.x"},{"entity":"RenderHomeCardDetails"},{"entity":"PopularRecipes"}]},
        "itemMeta":{},"actions":[${action("media", "MAIN_MEDIA")},${action("ctaButton", "SHOW_RECIPE_CTA")},
        {"name":"open_HomeCard_Settings","viewId":"inlineCustomizationControlSettings","deviceActions":[{"type":"StartActivityFromUri","data":{}}]}]}}""",
    )
    private val weatherCard = obj("""{"id":"pull://w/1","data":{"interactionContext":{"entities":[{"entity":"Weather"}]},"actions":[]}}""")
    private val tap = Http.json.parseToJsonElement(
        """[{"itemMeta":{},"entities":[{"entity":"amzn1.alexa.kitchen.document.x"},{"entity":"RenderHomeCardDetails"},{"entity":"PopularRecipes"}]}]""",
    ) as JsonArray

    @Test fun picksTheTappedPart() {
        val e = HomeCardActions.eventFor(listOf(weatherCard, recipeCard), tap, "ctaButton")!!
        assertEquals("Alexa.Presentation.APL", e.namespace)
        assertEquals("UserEvent", e.name)
        assertEquals("amzn1.as-tt.v1.Domain:Kitchen#TID#Domain:Kitchen PresentationToken", (e.payload["presentationToken"] as kotlinx.serialization.json.JsonPrimitive).content)
        assert(e.payload.toString().contains("SHOW_RECIPE_CTA"))
    }

    @Test fun unknownComponentFallsBackToMainMedia() {
        val e = HomeCardActions.eventFor(listOf(recipeCard), tap, "someUid123")!!
        assert(e.payload.toString().contains("MAIN_MEDIA"))
    }

    @Test fun cardWithoutEventGivesNothing() {
        val t = Http.json.parseToJsonElement("""[{"itemMeta":{},"entities":[{"entity":"Weather"}]}]""") as JsonArray
        assertNull(HomeCardActions.eventFor(listOf(weatherCard, recipeCard), t, null))
    }
}
