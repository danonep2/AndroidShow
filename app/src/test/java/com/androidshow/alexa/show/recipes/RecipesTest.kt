package com.androidshow.alexa.show.recipes

import com.androidshow.alexa.net.Http
import com.androidshow.alexa.show.ShowScreen
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatos sintéticos: o real ainda vem do levantamento em docs/recipes-protocol.md. */
class RecipesTest {
    private fun obj(s: String) = Http.json.parseToJsonElement(s) as JsonObject

    private val document = obj(
        """{"type":"APL","import":[{"name":"kitchen_package","version":"1.0"}],
        "mainTemplate":{"items":[{"type":"Sequence","data":"${'$'}{payload.list.recipes}",
        "item":{"type":"TouchWrapper","onPress":{"type":"SendEvent",
        "arguments":["recipeSelected","${'$'}{data.recipeId}","${'$'}{ordinal}"]}}}]}}""",
    )

    private val list = obj(
        """{"list":{"headerTitle":"Receitas de bolo","recipes":[
        {"recipeId":"r1","title":"Bolo de cenoura","imageUrl":"https://x/1.jpg","rating":4.5,"totalTime":"50 min","sourceName":"Tudo Gostoso"},
        {"recipeId":"r2","title":"Bolo de fubá","image":{"sources":[{"url":"https://x/2.jpg"}]},"ratingImage":"https://x/vdna_rating_stars_4_0.png"}
        ]}}""",
    )

    @Test fun readsResultList() {
        val v = Recipes.parse(ShowScreen.Apl("t", document, list)) as RecipeView.Results
        assertEquals("Receitas de bolo", v.title)
        assertEquals(2, v.recipes.size)
        assertEquals("Bolo de cenoura", v.recipes[0].title)
        assertEquals(4.5, v.recipes[0].rating!!, 0.0)
        assertEquals("50 min", v.recipes[0].time)
        assertEquals("Tudo Gostoso", v.recipes[0].source)
        assertEquals("https://x/2.jpg", v.recipes[1].image)
        assertEquals(4.0, v.recipes[1].rating!!, 0.0)
    }

    @Test fun touchResolvesItemBindings() {
        val v = Recipes.parse(ShowScreen.Apl("t", document, list)) as RecipeView.Results
        val args = RecipeTouch.argumentsFor(document, list, v.recipes[1])
        assertEquals("""["recipeSelected","r2",2]""", args.toString())
    }

    @Test fun readsDetail() {
        val ds = obj(
            """{"recipe":{"title":"Bolo de cenoura","image":"https://x/1.jpg",
            "ingredients":[{"title":"Massa","items":["3 cenouras","2 xícaras de açúcar"]},{"title":"Cobertura","items":["1 colher de manteiga"]}],
            "steps":[{"text":"Bata as cenouras."},{"text":"Asse por 40 minutos."}]}}""",
        )
        val v = Recipes.parse(ShowScreen.Apl("t", document, ds)) as RecipeView.Detail
        assertEquals(listOf("# Massa", "3 cenouras", "2 xícaras de açúcar", "# Cobertura", "1 colher de manteiga"), v.recipe.ingredients)
        assertEquals(listOf("Bata as cenouras.", "Asse por 40 minutos."), v.recipe.steps)
    }

    @Test fun ignoresOtherDocuments() {
        val doc = obj("""{"type":"APL","import":[{"name":"alexa-layouts","version":"1.7.0"}]}""")
        val ds = obj("""{"items":[{"title":"Previsão","image":"https://x/a.png"},{"title":"Amanhã","image":"https://x/b.png"}]}""")
        assertNull(Recipes.parse(ShowScreen.Apl("t", doc, ds)))
    }

    @Test fun unresolvableBindingGivesNoAction() {
        val doc = obj(
            """{"type":"APL","import":[{"name":"kitchen_package"}],"mainTemplate":{"item":{"onPress":
            {"type":"SendEvent","arguments":["open","${'$'}{data.id + 1}"]}}}}""",
        )
        val v = Recipes.parse(ShowScreen.Apl("t", doc, list)) as RecipeView.Results
        assertNull(RecipeTouch.argumentsFor(doc, list, v.recipes[0]))
        assertNotNull(v.recipes[0].image)
        assertTrue(v.recipes.none { it.hasDetail })
    }

    // --- Nomes reais dos layouts kitchen (docs/recipes-protocol.md §3) ---

    private val kitchenDoc = obj("""{"type":"APL","import":[{"name":"kitchen_package_07_27_22","version":"1.0"}]}""")

    @Test fun kitchenListUsesTappableItemArguments() {
        val ds = obj(
            """{"recipeList":{"headerTitle":"Bolo","listItems":[
            {"primaryText":"Bolo de laranja","secondaryText":"Tudo Gostoso","backgroundImageSource":"https://x/a._SL500_.jpg",
             "icon":"https://x/tudo-gostoso-logo-2.png","stars":"https://x/vdna_rating_stars_4_0.png","ratings":"120","time":"45 min",
             "eventAction":"openRecipe","eventData":"abc"},
            {"primaryText":"Bolo de milho","backgroundImageSource":"https://x/b.jpg","eventAction":"openRecipe","eventData":"def"}]}}""",
        )
        val v = Recipes.parse(ShowScreen.Apl("t", kitchenDoc, ds)) as RecipeView.Results
        val r = v.recipes[0]
        assertEquals("Bolo de laranja", r.title)
        assertEquals("Tudo Gostoso", r.source)
        assertEquals("https://x/tudo-gostoso-logo-2.png", r.sourceLogo)
        assertEquals(4.0, r.rating!!, 0.0)
        assertEquals("120", r.ratingCount)
        assertEquals("45 min", r.time)
        assertEquals("""["openRecipe","def"]""", RecipeTouch.argumentsFor(kitchenDoc, ds, v.recipes[1]).toString())
    }

    @Test fun kitchenDetailWithFooterButton() {
        val ds = obj(
            """{"recipe":{"header":{"headerTitle":"Bolo de laranja","headerAttributionText":"Tudo Gostoso"},
            "ingredients":{"title":"Ingredientes","componentListItems":[
              {"title":"Massa","listItems":[{"itemText":"3 ovos"},{"itemText":"1 xícara de suco"}]},
              {"title":"Calda","listItems":[{"itemText":"1 xícara de açúcar"}]}]},
            "instructions":{"title":"Modo de preparo","instructions":[{"stepText":"Bata tudo."},{"stepText":"Asse."}]},
            "footer":{"buttons":[{"buttonText":"Começar","sendEventArg":"startCooking"}]}}}""",
        )
        val v = Recipes.parse(ShowScreen.Apl("t", kitchenDoc, ds)) as RecipeView.Detail
        assertEquals("Bolo de laranja", v.recipe.title)
        assertEquals("Tudo Gostoso", v.recipe.source)
        assertEquals(listOf("# Massa", "3 ovos", "1 xícara de suco", "# Calda", "1 xícara de açúcar"), v.recipe.ingredients)
        assertEquals(listOf("Bata tudo.", "Asse."), v.recipe.steps)
        assertEquals("""["startCooking"]""", v.startAction?.arguments.toString())
        assertTrue(!v.cooking)
    }

    @Test fun stepByStepDocumentStartsCooking() {
        val ds = obj("""{"steps":{"listItems":[{"stepText":"Bata.","stepSpeech":"<speak>Bata.</speak>"},{"stepText":"Asse.","stepSpeech":"Asse."}]}}""")
        val v = Recipes.parse(ShowScreen.Apl("t", kitchenDoc, ds)) as RecipeView.Detail
        assertTrue(v.cooking)
        assertEquals(listOf("Bata.", "Asse."), v.recipe.steps)
    }

    @Test fun executeCommandsBecomeStepMoves() {
        val cmds = obj(
            """{"commands":[{"type":"Sequential","commands":[
            {"type":"SetPage","componentId":"stepPager","position":"relative","value":1},
            {"type":"SpeakItem","componentId":"step2"}]},
            {"type":"ScrollToIndex","componentId":"instructionsSequence","index":3},
            {"type":"ScrollToIngredients"}]}""",
        )["commands"]!!
        assertEquals(
            listOf(RecipeCommand.Move(1), RecipeCommand.GoTo(3), RecipeCommand.ShowIngredients),
            RecipeCommand.from(cmds),
        )
    }

    // --- Formato observado no Redmi (Domain:Kitchen, 2026-11-07) ---

    @Test fun observedResultsSelectByOrdinal() {
        val ds = obj(
            """{"resultsViewModel":{"title":"Bife","hintText":"","results":[
            {"provider":"TudoGostoso","author":"Por Juliana","ratings":"10","imageUrl":"https://x/1.jpg",
             "providerIconUrl":"https://x/tudo-gostoso-logo-2.png","action":"selectByOrdinal","time":"30min","stars":4.7,"id":"a","title":"Bife","type":"RECIPE"},
            {"provider":"TudoGostoso","author":"Por Raphael","ratings":"68","imageUrl":"https://x/2.jpg","action":"selectByOrdinal","time":"30min","stars":4.6,"id":"b","title":"Bife acebolado","type":"RECIPE"}]}}""",
        )
        val v = Recipes.parse(ShowScreen.Apl("t", kitchenDoc, ds)) as RecipeView.Results
        assertEquals("Bife", v.title)
        assertEquals(null, v.subtitle)
        val r = v.recipes[1]
        assertEquals("Bife acebolado", r.title)
        assertEquals("TudoGostoso", r.source)
        assertEquals("Por Raphael", r.author)
        assertEquals(4.6, r.rating!!, 0.0)
        assertEquals("68", r.ratingCount)
        assertEquals("""["selectByOrdinal",2]""", RecipeTouch.argumentsFor(kitchenDoc, ds, r).toString())
    }

    @Test fun observedDetail() {
        val ds = obj(
            """{"detailsViewModel":{"title":"","recipeName":"Bife acebolado","recipeInfo":"30min | 1 porções","itemRatingCount":"68",
            "itemRatingImageUrl":"https://x/vdna_rating_stars_4_5.png","authorInfo":"Receita de Raphael","backgroundImageUrl":"",
            "recipeImageUrl":"https://x/r.jpg","headerAttributionImage":"https://x/logo.png",
            "instructions":[{"stepText":"Coloque a carne."},{"stepText":"Frite."}],
            "instructionsComponentList":{"title":"Instruções","componentListItems":[{"listItems":[{"itemText":"Coloque a carne."},{"itemText":"Frite."}]}]},
            "ingredientsComponentList":{"title":"Ingredientes","componentListItems":[{"listItems":[{"itemText":"200 g de carne"},{"itemText":"2 cebolas"}]}]},
            "buttons":[{"buttonText":"Iniciar receita","display":"normal","disabled":false,"sendEventArg":"startCookAlong"},
              {"buttonText":"Salvar receita","display":"normal","disabled":false,"sendEventArg":"saveRecipe"},
              {"buttonText":"Remover das Receitas salvas","display":"none","disabled":false,"sendEventArg":"removeSavedRecipe"},
              {"buttonText":"Adicionado","display":"none","disabled":true,"sendEventArg":"addedToShoppingList"}],
            "visualContext":"{\"cookAlongStepOrdinal\":1}"}}""",
        )
        val v = Recipes.parse(ShowScreen.Apl("t", kitchenDoc, ds)) as RecipeView.Detail
        val r = v.recipe
        assertEquals("Bife acebolado", r.title)
        assertEquals("https://x/r.jpg", r.image)
        assertEquals("30min", r.time)
        assertEquals("1 porções", r.servings)
        assertEquals(4.5, r.rating!!, 0.0)
        assertEquals("68", r.ratingCount)
        assertEquals("Receita de Raphael", r.author)
        assertEquals("https://x/logo.png", r.sourceLogo)
        assertEquals(listOf("200 g de carne", "2 cebolas"), r.ingredients)
        assertEquals(listOf("Coloque a carne.", "Frite."), r.steps)
        assertEquals("""["startCookAlong"]""", v.startAction?.arguments.toString())
        assertEquals(listOf("Salvar receita"), v.extraActions.map { it.label })
        assertTrue(!v.cooking)
    }

    @Test fun observedStepByStep() {
        val ds = obj(
            """{"stepByStepViewModel":{"recipeName":"Bife acebolado","visualContext":"{\"navigationContext\":{},\"cookAlongStepOrdinal\":2}",
            "ingredientsComponentList":{"title":"Ingredientes","componentListItems":[{"listItems":[{"itemText":"Azeite"},{"itemText":"Sal"}]}]},
            "properties":{"instructions":[{"stepSpeech":"https://x/1.mp3","stepImageURL":"","stepText":"Um."},{"stepSpeech":"https://x/2.mp3","stepImageURL":"","stepText":"Dois."},{"stepSpeech":"https://x/3.mp3","stepText":"Três."}]}}}""",
        )
        val v = Recipes.parse(ShowScreen.Apl("t", kitchenDoc, ds)) as RecipeView.Detail
        assertTrue(v.cooking)
        assertEquals(1, v.step)
        assertEquals("Bife acebolado", v.recipe.title)
        assertEquals(listOf("Azeite", "Sal"), v.recipe.ingredients)
        assertEquals("https://x/2.mp3", v.recipe.stepSpeech[1])
        assertEquals(
            listOf(RecipeCommand.GoTo(1)),
            RecipeCommand.from(obj("""{"c":[{"type":"OnPlayUpdate","stepOrdinal":2}]}""")["c"]!!),
        )
    }

    @Test fun observedIngredientsGenericList() {
        val ds = obj(
            """{"genericListViewModel":{"listItems":[{"itemText":"200 g de carne bovina"},{"itemText":"Azeite"}],"displayOrdinal":false,
            "visualContext":"{\"cookAlongStepOrdinal\":2}","providerLogoUrl":"https://x/tudo-gostoso-logo-2.png","title":"Ingredientes","backgroundImageUrl":""}}""",
        )
        val v = Recipes.parse(ShowScreen.Apl("t", kitchenDoc, ds)) as RecipeView.GenericList
        assertEquals("Ingredientes", v.title)
        assertEquals(listOf("200 g de carne bovina", "Azeite"), v.items)
        assertEquals("https://x/tudo-gostoso-logo-2.png", v.logo)
        assertTrue(v.isIngredients)
        assertTrue(!v.numbered)
    }
}
