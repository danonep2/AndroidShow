package com.androidshow.alexa.show.recipes

import com.androidshow.alexa.show.ShowScreen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Receitas desenhadas pelo próprio app, no lugar do APL da cozinha da Amazon.
 *
 * Formato observado no Redmi (skill "Domain:Kitchen", dumps de 2026-11-07):
 * - lista: `resultsViewModel.results[]` {title, imageUrl, provider, providerIconUrl, author, stars,
 *   ratings, time, action:"selectByOrdinal"}; o item é um TappableItem `[data.action, ordinal]`;
 * - detalhe: `detailsViewModel` {recipeName, recipeImageUrl, recipeInfo "30min | 1 porções",
 *   itemRatingCount, itemRatingImageUrl, authorInfo, ingredientsComponentList, instructions[{stepText}],
 *   buttons[{buttonText, sendEventArg, display}]};
 * - passo a passo: `stepByStepViewModel` {recipeName, ingredientsComponentList,
 *   properties.instructions[{stepText, stepSpeech (mp3)}], visualContext.cookAlongStepOrdinal}.
 * Nomes alternativos (parâmetros dos layouts dos pacotes kitchen) ficam como reserva.
 */
data class Recipe(
    val index: Int,
    val title: String,
    val image: String? = null,
    val rating: Double? = null,
    val ratingCount: String? = null,
    val time: String? = null,
    val servings: String? = null,
    val difficulty: String? = null,
    val source: String? = null,
    val sourceLogo: String? = null,
    val author: String? = null,
    val ingredients: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    /** Áudio de cada passo (stepSpeech), lido quando o passo é escolhido na tela. */
    val stepSpeech: List<String?> = emptyList(),
    /** Argumentos do toque no item (`[action, ordinal]` ou `[eventAction, eventData]`). */
    val action: JsonArray? = null,
    /** Item original do datasource: resolve os `${...}` do SendEvent quando [action] não vem pronto. */
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val hasDetail get() = ingredients.isNotEmpty() || steps.isNotEmpty()
}

/** Botão do documento (rodapé do detalhe: "Iniciar receita", "Salvar receita"...). */
data class RecipeAction(val label: String, val arguments: JsonArray) {
    /** O botão que abre o passo a passo [Observado: sendEventArg "startCookAlong"]. */
    val startsCooking get() = arguments.toString().contains("CookAlong", ignoreCase = true) || START.containsMatchIn(label)

    private companion object {
        val START = Regex("(?i)come[cç]|inici|start|cozinh|passo a passo")
    }
}

sealed interface RecipeView {
    /** Identidade da tela: a nuvem reenvia o mesmo documento a cada "próximo passo". */
    val key: String

    /** Resultado de uma busca ("Alexa, receitas de bolo"). */
    data class Results(val title: String, val subtitle: String?, val recipes: List<Recipe>) : RecipeView {
        override val key get() = "results:$title:${recipes.size}:${recipes.firstOrNull()?.title}"
    }

    /**
     * Uma receita aberta. [cooking]: documento do passo a passo, que a nuvem avança por
     * ExecuteCommands (OnPlayUpdate) quando se diz "próximo passo"; [step] é o passo atual (0…).
     */
    data class Detail(
        val recipe: Recipe,
        val actions: List<RecipeAction> = emptyList(),
        val cooking: Boolean = false,
        val step: Int = 0,
    ) : RecipeView {
        override val key get() = "detail:$cooking:${recipe.title}"
        val startAction get() = actions.firstOrNull { it.startsCooking }
        /** Os outros botões visíveis (salvar, lista de compras...). */
        val extraActions get() = actions.filter { !it.startsCooking }
    }

    /**
     * Lista avulsa da cozinha [Observado: "Alexa, mostrar ingredientes" no passo a passo manda
     * `genericListViewModel` {title "Ingredientes", listItems[{itemText}], providerLogoUrl}].
     */
    data class GenericList(val title: String, val items: List<String>, val logo: String?, val numbered: Boolean) : RecipeView {
        override val key get() = "list:$title"
        val isIngredients get() = title.contains("ingrediente", ignoreCase = true)
    }
}

object Recipes {

    /** Lê a tela APL como receitas, ou null se não for (aí o APL da Amazon desenha). */
    fun parse(screen: ShowScreen.Apl): RecipeView? {
        val ds = screen.datasources ?: return null
        if (!looksLikeRecipes(screen.document, ds)) return null
        return detail(ds) ?: results(ds) ?: genericList(ds)
    }

    /** Título + listItems[{itemText}] (genericListViewModel / ListOverlay). */
    private fun genericList(ds: JsonObject): RecipeView.GenericList? {
        val holder = objects(ds).firstOrNull { o ->
            (o["listItems"] as? JsonArray)?.any { (it as? JsonObject)?.containsKey("itemText") == true } == true
        } ?: return null
        val items = (holder["listItems"] as JsonArray).mapNotNull(::lineOf).map(::clean).filter { it.isNotBlank() }
        if (items.isEmpty()) return null
        return RecipeView.GenericList(
            title = text(holder, listOf("title", "headertitle"))?.let(::clean) ?: "Lista",
            items = items,
            logo = image(holder, listOf("providerlogourl") + LOGO_KEYS),
            numbered = (holder["displayOrdinal"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    /** Detalhe ou passo a passo: ingredientes (ComponentList) e/ou passos (instructions[].stepText). */
    private fun detail(ds: JsonObject): RecipeView.Detail? {
        val ingredients = ingredients(ds)
        val stepObjs = stepObjects(ds)
        val steps = stepObjs.mapNotNull { text(it, listOf("steptext")) }.map(::clean).ifEmpty { lines(ds) { k -> STEP_HINTS.any { it in k } } }
        if (ingredients.size + steps.size < 2) return null
        val header = objects(ds).firstOrNull { it.containsKey("headerTitle") }
        val title = firstText(ds, listOf("recipename", "recipetitle"))
            ?: header?.let { text(it, listOf("headertitle")) }
            ?: firstText(ds, listOf("primarytext", "title"))?.takeUnless { it.isSectionLabel() }
            ?: "Receita"
        val info = firstText(ds, listOf("recipeinfo")).orEmpty().split('|').map { it.trim() }
        val cooking = stepObjs.any { it.containsKey("stepSpeech") }
        val recipe = Recipe(
            index = 0,
            title = clean(title),
            image = firstImage(ds, listOf("recipeimageurl", "headerbackgroundimage") + IMAGE_KEYS),
            rating = objects(ds).firstNotNullOfOrNull { rating(it) },
            ratingCount = firstText(ds, RATING_COUNT_KEYS),
            time = info.firstOrNull { TIME_INFO.containsMatchIn(it) } ?: firstText(ds, TIME_KEYS),
            servings = info.firstOrNull { SERVINGS_INFO.containsMatchIn(it) } ?: firstText(ds, SERVINGS_KEYS),
            difficulty = firstText(ds, DIFFICULTY_KEYS),
            source = header?.let { text(it, listOf("headerattributiontext")) } ?: firstText(ds, listOf("provider", "sourcename")),
            sourceLogo = firstImage(ds, LOGO_KEYS),
            author = firstText(ds, listOf("authorinfo", "author")),
            ingredients = ingredients,
            steps = steps,
            stepSpeech = if (stepObjs.size == steps.size) stepObjs.map { o -> (o["stepSpeech"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") } } else emptyList(),
        )
        val step = (cookAlongOrdinal(ds) ?: 1) - 1
        return RecipeView.Detail(recipe, actions(ds), cooking, step.coerceIn(0, (steps.size - 1).coerceAtLeast(0)))
    }

    /** Lista: o maior array de objetos com título e imagem. */
    private fun results(ds: JsonObject): RecipeView.Results? {
        val list = arrays(ds)
            .map { arr -> arr.mapNotNull { it as? JsonObject } }
            .filter { it.size >= 2 }
            .map { objs -> objs.mapIndexedNotNull { i, o -> listItem(i, o) } }
            .filter { r -> r.size >= 2 && r.count { it.image != null } >= r.size / 2 }
            .maxByOrNull { it.size } ?: return null
        return RecipeView.Results(
            // find() não entra em arrays: "title" aqui é o do cabeçalho, não o de um item.
            title = firstText(ds, listOf("headertitle", "pagetitle", "searchtitle", "title")) ?: "Receitas",
            subtitle = firstText(ds, listOf("headersubtitle", "hinttext")),
            recipes = list,
        )
    }

    private fun listItem(index: Int, o: JsonObject): Recipe? {
        // HomepageGenericResultElement guarda tudo dentro de "data".
        val d = o["data"] as? JsonObject ?: o
        val title = text(d, TITLE_KEYS)?.takeIf { it.isNotBlank() } ?: return null
        return Recipe(
            index = index,
            title = clean(title),
            image = image(d, IMAGE_KEYS),
            rating = rating(d),
            ratingCount = text(d, RATING_COUNT_KEYS),
            time = text(d, TIME_KEYS)?.let(::clean),
            servings = text(d, SERVINGS_KEYS)?.let(::clean),
            difficulty = text(d, DIFFICULTY_KEYS)?.let(::clean),
            source = text(d, SOURCE_KEYS)?.let(::clean),
            sourceLogo = image(d, LOGO_KEYS) ?: urls(d).firstOrNull { "logo" in it.lowercase() },
            author = text(d, listOf("author", "authorinfo"))?.let(::clean),
            action = action(o, index),
            raw = o,
        )
    }

    /**
     * Toque no item: `[action, ordinal]` dos resultados [Observado: TappableItem eventAction
     * "${data.action}", eventData "${ordinal}"], `[eventAction, eventData]` ou `[sendEventArg]`.
     */
    private fun action(o: JsonObject, index: Int? = null): JsonArray? {
        val d = o["data"] as? JsonObject ?: o
        val ev = find(o, depth = 2) { k, v -> k == "eventaction" && v is JsonPrimitive }
        if (ev != null) {
            val data = find(o, depth = 2) { k, _ -> k == "eventdata" } ?: JsonPrimitive("")
            return JsonArray(listOf(ev, data))
        }
        if (index != null) (d["action"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
            return JsonArray(listOf(it, JsonPrimitive(index + 1)))
        }
        return (find(o, depth = 2) { k, _ -> k == "sendeventarg" || k == "eventarguments" })?.let {
            it as? JsonArray ?: JsonArray(listOf(it))
        }
    }

    /** Botões visíveis e habilitados (os de `display: "none"` são estados alternativos). */
    private fun actions(ds: JsonObject): List<RecipeAction> = objects(ds).mapNotNull { o ->
        if (o["eventAction"] == null && o["sendEventArg"] == null && o["eventArguments"] == null) return@mapNotNull null
        if ((o["display"] as? JsonPrimitive)?.contentOrNull == "none") return@mapNotNull null
        if ((o["disabled"] as? JsonPrimitive)?.contentOrNull == "true") return@mapNotNull null
        val label = text(o, BUTTON_LABEL_KEYS) ?: return@mapNotNull null
        val args = action(o) ?: return@mapNotNull null
        RecipeAction(clean(label), args)
    }.distinctBy { it.arguments.toString() }.toList()

    /** Passo atual no documento: visualContext (JSON em texto) → cookAlongStepOrdinal (1…). */
    private fun cookAlongOrdinal(ds: JsonObject): Int? =
        objects(ds).firstNotNullOfOrNull { o ->
            (o["cookAlongStepOrdinal"] as? JsonPrimitive)?.intOrNull
                ?: (o["visualContext"] as? JsonPrimitive)?.contentOrNull?.let { COOK_ORDINAL.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        }

    /** Ingredientes: a ComponentList sob uma chave "ingredient*" (o detalhe tem outra só para as instruções). */
    private fun ingredients(ds: JsonObject): List<String> {
        val list = objects(ds).flatMap { o -> o.entries.filter { "ingredient" in it.key.lowercase() }.map { it.value } }
            .mapNotNull { (it as? JsonObject)?.get("componentListItems") as? JsonArray }
            .firstOrNull()
            ?: arrays(ds, key = "componentlistitems").firstOrNull { !it.toString().contains("stepText") && !hasInstructionsTitle(ds, it) }
        if (list != null) {
            val groups = list.mapNotNull { it as? JsonObject }
            val titled = groups.count { !text(it, listOf("title")).isNullOrBlank() } > 1
            return groups.flatMap { g ->
                val head = text(g, listOf("title"))?.takeIf { titled }?.let { "# ${clean(it)}" }
                val items = (g["listItems"] as? JsonArray ?: g["indentListItems"] as? JsonArray).orEmpty().mapNotNull(::lineOf)
                listOfNotNull(head) + items
            }.map(::clean).filter { it.isNotBlank() }
        }
        return lines(ds) { "ingredient" in it }
    }

    private fun hasInstructionsTitle(ds: JsonObject, items: JsonArray): Boolean =
        objects(ds).any { it["componentListItems"] == items && (it["title"] as? JsonPrimitive)?.contentOrNull?.isSectionLabelOfSteps() == true }

    /** Objetos dos passos: o maior array de objetos com stepText. */
    private fun stepObjects(ds: JsonObject): List<JsonObject> =
        arrays(ds).map { arr -> arr.mapNotNull { it as? JsonObject }.filter { it.containsKey("stepText") } }
            .maxByOrNull { it.size }.orEmpty()

    /** Documento da cozinha (pacotes kitchen_*) ou datasource com cara de receita. */
    private fun looksLikeRecipes(document: JsonObject, ds: JsonObject): Boolean {
        val imports = (document["import"] as? JsonArray).orEmpty().joinToString { it.toString() }
        if (imports.contains("kitchen", ignoreCase = true) || imports.contains("recipe", ignoreCase = true)) return true
        return hasKey(ds) { "recipe" in it || "ingredient" in it || it == "steptext" || it == "componentlistitems" }
    }

    // --- Leitura genérica de JSON -------------------------------------------------------------

    private fun hasKey(e: JsonElement, match: (String) -> Boolean): Boolean = when (e) {
        is JsonObject -> e.any { (k, v) -> match(k.lowercase()) || hasKey(v, match) }
        is JsonArray -> e.any { hasKey(it, match) }
        else -> false
    }

    /** Arrays do JSON; com [key], só os que estão sob essa chave (minúsculas). */
    private fun arrays(e: JsonElement, key: String? = null, under: String? = null): Sequence<JsonArray> = sequence {
        when (e) {
            is JsonArray -> {
                if (key == null || under == key) yield(e)
                e.forEach { yieldAll(arrays(it, key)) }
            }
            is JsonObject -> e.forEach { (k, v) -> yieldAll(arrays(v, key, k.lowercase())) }
            else -> Unit
        }
    }

    private fun objects(e: JsonElement): Sequence<JsonObject> = sequence {
        when (e) {
            is JsonObject -> { yield(e); e.values.forEach { yieldAll(objects(it)) } }
            is JsonArray -> e.forEach { yieldAll(objects(it)) }
            else -> Unit
        }
    }

    /** Primeiro valor (busca em largura, até [depth] níveis) cuja chave satisfaz [match]. */
    private fun find(o: JsonObject, depth: Int = 3, match: (String, JsonElement) -> Boolean): JsonElement? {
        var level: List<JsonObject> = listOf(o)
        repeat(depth) {
            for (obj in level) for ((k, v) in obj) if (v !is JsonNull && match(k.lowercase(), v)) return v
            level = level.flatMap { obj -> obj.values.mapNotNull { it as? JsonObject } }
        }
        return null
    }

    /** Texto da primeira chave de [keys] (em ordem de preferência) que tenha valor. */
    private fun text(o: JsonObject, keys: List<String>): String? = keys.firstNotNullOfOrNull { key ->
        find(o) { k, v -> k == key && (v is JsonPrimitive || (v is JsonObject && v.prim("text") != null)) }?.let {
            when (it) {
                is JsonPrimitive -> it.contentOrNull
                is JsonObject -> it.prim("text")
                else -> null
            }
        }?.takeIf { it.isNotBlank() && !it.startsWith("\${") && !it.startsWith("http") }
    }

    private fun firstText(ds: JsonObject, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { k -> objects(ds).firstNotNullOfOrNull { text(it, listOf(k)) } }?.let(::clean)

    /** URL da primeira chave de [keys] (em ordem de preferência) que tenha imagem. */
    private fun image(o: JsonObject, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> find(o) { k, _ -> k == key }?.let(::urlOf) }

    private fun firstImage(ds: JsonObject, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { k -> objects(ds).firstNotNullOfOrNull { image(it, listOf(k)) } }

    private fun urlOf(e: JsonElement): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.takeIf { it.startsWith("http") }
        is JsonObject -> e.prim("url") ?: e.prim("src")
            ?: (e["sources"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.prim("url") }?.lastOrNull()
        is JsonArray -> e.firstNotNullOfOrNull(::urlOf)
        else -> null
    }

    private fun urls(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> listOfNotNull(e.contentOrNull?.takeIf { it.startsWith("http") })
        is JsonObject -> e.values.flatMap(::urls)
        is JsonArray -> e.flatMap(::urls)
        else -> emptyList()
    }

    private fun lineOf(e: JsonElement): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonObject -> text(e, LINE_KEYS)
        else -> null
    }

    /** Lista de textos sob uma chave (ingredientes, passos): strings ou objetos com texto. */
    private fun lines(o: JsonObject, key: (String) -> Boolean): List<String> {
        val v = find(o, depth = 5) { k, v -> key(k) && v is JsonArray } as? JsonArray ?: return emptyList()
        return v.flatMap { item ->
            when (item) {
                is JsonPrimitive -> listOfNotNull(item.contentOrNull)
                is JsonObject -> {
                    // Grupos ("Massa", "Cobertura") com sublista: título em destaque + itens.
                    val sub = item.values.firstOrNull { it is JsonArray } as? JsonArray
                    val head = text(item, LINE_KEYS)
                    if (sub != null) listOfNotNull(head?.let { "# $it" }) + sub.mapNotNull(::lineOf)
                    else listOfNotNull(head)
                }
                else -> emptyList()
            }
        }.map(::clean).filter { it.isNotBlank() && it != "#" }
    }

    /** `stars` numérico ou a imagem de estrelas (vdna_rating_stars_4_5.png → 4,5). */
    private fun rating(o: JsonObject): Double? =
        RATING_KEYS.firstNotNullOfOrNull { key -> find(o) { k, _ -> k == key }?.let(::number) }
            ?: urls(o).firstNotNullOfOrNull { STARS_IMAGE.find(it) }?.let { m -> "${m.groupValues[1]}.${m.groupValues[2]}".toDoubleOrNull() }

    private fun number(e: JsonElement): Double? = when (e) {
        is JsonPrimitive -> e.doubleOrNull ?: e.contentOrNull?.replace(',', '.')?.toDoubleOrNull()
        is JsonObject -> e.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.doubleOrNull }
        else -> null
    }?.takeIf { it in 0.0..5.0 }

    private fun String.isSectionLabel() = SECTION_LABEL.matches(trim())
    private fun String.isSectionLabelOfSteps() = STEPS_LABEL.matches(trim())

    /** Tira marcação SSML/HTML simples que às vezes vem nos textos. */
    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim()

    private fun JsonObject.prim(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private val STARS_IMAGE = Regex("""stars?_(\d)_(\d)""")
    private val COOK_ORDINAL = Regex(""""cookAlongStepOrdinal"\s*:\s*(\d+)""")
    private val TIME_INFO = Regex("""(?i)\d\s*(min|h\b|hora)""")
    private val SERVINGS_INFO = Regex("""(?i)por[cç][aãoõ]|serve|pessoa|rendimento""")
    private val SECTION_LABEL = Regex("(?i)ingredientes?|modo de preparo|preparo|instru[cç][oõ]es|passos?")
    private val STEPS_LABEL = Regex("(?i)modo de preparo|preparo|instru[cç][oõ]es|passos?")
    private val TITLE_KEYS = listOf("title", "primarytext", "recipetitle", "recipename", "name", "headline")
    private val IMAGE_KEYS = listOf("imageurl", "backgroundimagesource", "recipeimageurl", "image", "imagesource", "backgroundimage", "thumbnail", "recipeimage", "heroimage", "backgroundimageurl")
    private val RATING_KEYS = listOf("stars", "rating", "ratingvalue", "averagerating", "starrating")
    private val RATING_COUNT_KEYS = listOf("ratings", "itemratingcount", "ratingcount", "reviewcount", "numberofratings")
    private val TIME_KEYS = listOf("time", "totaltime", "cooktime", "preptime", "duration")
    private val SERVINGS_KEYS = listOf("servings", "yield", "serves", "portions")
    private val DIFFICULTY_KEYS = listOf("difficulty")
    private val SOURCE_KEYS = listOf("provider", "sourcename", "secondarytext", "attributiontext", "source", "publisher")
    private val LOGO_KEYS = listOf("providericonurl", "headerattributionimage", "icon", "attributionlogo", "sourcelogo", "providerlogo", "logo", "logourl")
    private val LINE_KEYS = listOf("itemtext", "steptext", "text", "title", "name", "description")
    private val BUTTON_LABEL_KEYS = listOf("buttontext", "displaytext", "text", "label", "primarytext", "title", "accessibilitylabel")
    private val STEP_HINTS = listOf("step", "instruction", "direction", "method", "preparation", "preparo")
}
