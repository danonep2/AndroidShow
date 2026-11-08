package com.androidshow.alexa.show.recipes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Comandos APL que a nuvem manda por ExecuteCommands no passo a passo ("Alexa, próximo passo"),
 * traduzidos para a tela nativa. [Inferido] O conteúdo real dos ExecuteCommands ainda não foi
 * capturado: SetPage, ScrollToIndex e SpeakList são os comandos APL
 * que posicionam uma lista; ScrollToIngredients/ScrollToInstructions são comandos do kitchen_package.
 */
sealed interface RecipeCommand {
    data class GoTo(val step: Int) : RecipeCommand
    data class Move(val delta: Int) : RecipeCommand
    data object ShowIngredients : RecipeCommand
    data object ShowSteps : RecipeCommand
    /** A nuvem abriu a lista de ingredientes ("mostrar ingredientes"); fechar volta a ela com `["back"]`. */
    data object OpenIngredientsList : RecipeCommand

    companion object {
        fun from(commands: JsonElement): List<RecipeCommand> = buildList { collect(commands, this) }

        private fun collect(e: JsonElement, out: MutableList<RecipeCommand>) {
            when (e) {
                is JsonArray -> e.forEach { collect(it, out) }
                is JsonObject -> {
                    command(e)?.let(out::add)
                    // Sequential, Parallel, Select...: comandos aninhados.
                    for (key in listOf("commands", "sequence", "otherwise", "finally")) e[key]?.let { collect(it, out) }
                }
                else -> Unit
            }
        }

        private fun command(o: JsonObject): RecipeCommand? {
            val type = o.s("type") ?: return null
            val component = o.s("componentId").orEmpty().lowercase()
            return when (type) {
                // [Observado no Redmi] "Alexa, próximo passo": {"type":"OnPlayUpdate","stepOrdinal":2}, comando
                // do documento do passo a passo que rola até "step${stepOrdinal}Wrapper" (ordinal começa em 1).
                "OnPlayUpdate", "SelectStepAndUpdateContext" -> o.i("stepOrdinal")?.let { GoTo(it - 1) }
                "SetPage" -> {
                    val value = o.i("value") ?: return null
                    if (o.s("position") == "absolute") GoTo(value) else Move(value)
                }
                "ScrollToIndex", "SpeakList" -> (o.i("index") ?: o.i("start"))?.let(::GoTo)
                "ScrollToIngredients" -> ShowIngredients
                "ScrollToInstructions" -> ShowSteps
                "ScrollToComponent", "SpeakItem" -> when {
                    "ingredient" in component -> ShowIngredients
                    "instruction" in component -> ShowSteps
                    else -> null
                }
                else -> null
            }
        }

        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    }
}
