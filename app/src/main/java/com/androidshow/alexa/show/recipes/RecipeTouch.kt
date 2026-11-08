package com.androidshow.alexa.show.recipes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Toque numa receita desenhada pelo app. O documento APL continua sendo a fonte da ação: os
 * argumentos do SendEvent do item (ex.: `["selectRecipe", "${data.recipeId}"]`) são resolvidos
 * com os dados daquele item, e o resultado vira o mesmo Alexa.Presentation.APL.UserEvent que o
 * renderizador APL mandaria.
 */
object RecipeTouch {

    fun argumentsFor(document: JsonObject, datasources: JsonObject?, recipe: Recipe): JsonArray? =
        // TappableItem: arguments ["${eventAction}", "${eventData}"], com os valores no próprio item.
        recipe.action ?: sendEvents(document)
            .filter { args -> BINDS_ITEM.containsMatchIn(args.toString()) }
            .firstNotNullOfOrNull { resolve(it, recipe, datasources) }

    /** Argumentos de todos os comandos SendEvent do documento, na ordem em que aparecem. */
    private fun sendEvents(e: JsonElement): List<JsonArray> = when (e) {
        is JsonObject -> {
            val own = if ((e["type"] as? JsonPrimitive)?.content == "SendEvent") listOfNotNull(e["arguments"] as? JsonArray) else emptyList()
            own + e.values.flatMap(::sendEvents)
        }
        is JsonArray -> e.flatMap(::sendEvents)
        else -> emptyList()
    }

    /** null se alguma expressão não for um caminho simples (aí não dá para adivinhar o valor). */
    private fun resolve(args: JsonArray, recipe: Recipe, ds: JsonObject?): JsonArray? {
        val out = args.map { resolveElement(it, recipe, ds) ?: return null }
        return JsonArray(out)
    }

    private fun resolveElement(e: JsonElement, recipe: Recipe, ds: JsonObject?): JsonElement? = when {
        e is JsonPrimitive && e.isString -> resolveString(e.content, recipe, ds)
        e is JsonObject -> JsonObject(e.mapValues { resolveElement(it.value, recipe, ds) ?: return null })
        e is JsonArray -> JsonArray(e.map { resolveElement(it, recipe, ds) ?: return null })
        else -> e
    }

    private fun resolveString(s: String, recipe: Recipe, ds: JsonObject?): JsonElement? {
        val whole = BINDING.matchEntire(s)
        if (whole != null) return value(whole.groupValues[1].trim(), recipe, ds)
        var failed = false
        val text = BINDING.replace(s) { m ->
            when (val v = value(m.groupValues[1].trim(), recipe, ds)) {
                is JsonPrimitive -> v.content
                null -> { failed = true; "" }
                else -> v.toString()
            }
        }
        return if (failed) null else JsonPrimitive(text)
    }

    private fun value(expr: String, recipe: Recipe, ds: JsonObject?): JsonElement? {
        when (expr) {
            "index" -> return JsonPrimitive(recipe.index)
            "ordinal" -> return JsonPrimitive(recipe.index + 1)
            "data" -> return recipe.raw
        }
        if (!PATH.matches(expr)) return null
        val parts = expr.split('.')
        // Parâmetro de layout (ex.: ${eventAction}): o template o liga ao campo de mesmo nome do item.
        path(recipe.raw, parts)?.let { return it }
        (recipe.raw["data"] as? JsonObject)?.let { d -> path(d, parts)?.let { return it } }
        return if (parts.first() == "data") path(recipe.raw, parts.drop(1)) else ds?.let { path(it, parts) }
    }

    private fun path(root: JsonElement, parts: List<String>): JsonElement? =
        parts.fold(root as JsonElement?) { cur, p ->
            when (cur) {
                is JsonObject -> cur[p]
                is JsonArray -> p.toIntOrNull()?.let(cur::getOrNull)
                else -> null
            }
        }

    private val BINDING = Regex("""\$\{([^}]*)\}""")
    private val BINDS_ITEM = Regex("""\$\{\s*(data|index|ordinal|eventAction|eventData|sendEventArg)\b""")
    private val PATH = Regex("""[A-Za-z_][\w]*(\.[\w]+)*""")
}
