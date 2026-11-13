// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.OneShotKind
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.ListsStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeListTest {
    @Test fun asksInEnglish() {
        val r = RecipeList.ask("Add what I need for lemon chicken to the grocery list.")!!
        assertEquals("lemon chicken", r.dish)
        assertEquals("the grocery list", r.list)
        assertEquals(Lang.EN, r.lang)
        assertTrue(r.drops.isEmpty())
        assertEquals(ListRef.Role(ListRole.SHOPPING), ListsRouter.named(r.list))
        assertEquals("Lemon Chicken", RecipeList.ask("Robot, put everything I need to make a Lemon Chicken on my shopping list")!!.dish)
        assertEquals("pancakes", RecipeList.ask("add the ingredients for pancakes to my list")!!.dish)
    }

    @Test fun dropsInTheSameSentence() {
        val r = RecipeList.ask("Add what I need for lemon chicken to the grocery list, but not the oil, I have it")!!
        assertEquals("lemon chicken", r.dish)
        assertEquals("the grocery list", r.list)
        assertEquals(listOf("oil"), r.drops)
        assertEquals(listOf("salt", "pepper"), RecipeList.ask("add what I need for soup to my list except the salt and pepper")!!.drops)
    }

    @Test fun asksInFrench() {
        val r = RecipeList.ask("Ajoute ce qu’il me faut pour un poulet au citron à la liste d’épicerie, mais pas l’huile, j’en ai.")!!
        assertEquals("poulet au citron", r.dish)
        assertEquals("la liste d'épicerie", r.list)
        assertEquals(Lang.FR, r.lang)
        assertEquals(listOf("huile"), r.drops)
        assertEquals("crêpes", RecipeList.ask("mets les ingrédients des crêpes sur ma liste")!!.dish)
    }

    @Test fun aSharedRecipeHasNoDish() {
        val r = RecipeList.ask("add the ingredients from this recipe to my shopping list")!!
        assertNull(r.dish)
        assertEquals("my shopping list", r.list)
    }

    @Test fun notAnAsk() {
        assertNull(RecipeList.ask("add eggs to the grocery list"))
        assertNull(RecipeList.ask("what do I need for lemon chicken"))
        assertNull(RecipeList.ask("ajoute du lait à ma liste d'épicerie"))
    }

    @Test fun dropsFollowUp() {
        assertEquals(listOf("oil"), RecipeList.drops("Not the oil, I have it."))
        assertEquals(listOf("salt", "pepper"), RecipeList.drops("skip the salt and the pepper"))
        assertEquals(listOf("garlic"), RecipeList.drops("I already have garlic"))
        assertEquals(listOf("huile"), RecipeList.drops("Pas l’huile, j’en ai."))
        assertEquals(listOf("ail"), RecipeList.drops("sans l'ail"))
        assertNull(RecipeList.drops("what's the weather"))
    }

    @Test fun theDrop() {
        val rows = listOf("chicken thighs", "lemons", "olive oil", "garlic", "salt", "Huile d'olive")
        assertEquals(setOf(2, 5), RecipeList.match(rows, listOf("oil", "huile")))
        assertEquals(setOf(1), RecipeList.match(rows, listOf("lemon")))
        assertTrue(RecipeList.match(rows, listOf("butter")).isEmpty())
    }

    @Test fun readsASharedRecipe() {
        val text = """
            Lemon chicken
            Serves 4
            Ingredients
            - 4 chicken thighs
            • 2 lemons
            For the sauce:
            2 tbsp olive oil
            3 cloves garlic

            Method
            1. Heat the oven.
        """.trimIndent()
        assertEquals(listOf("4 chicken thighs", "2 lemons", "2 tbsp olive oil", "3 cloves garlic"), RecipeList.fromRecipe(text))
        assertEquals(listOf("farine", "œufs", "lait"), RecipeList.fromRecipe("Crêpes\nIngrédients : farine, œufs, lait\nPréparation\nMélange."))
        assertTrue(RecipeList.fromRecipe("Just a note about dinner").isEmpty())
    }

    @Test fun theModelsAnswer() {
        assertEquals(listOf("chicken thighs", "lemons", "olive oil"), RecipeList.parse("Here: [\"chicken thighs\", \"lemons\", \"olive oil\", \"Lemons\"]"))
        assertEquals(emptyList<String>(), RecipeList.parse("[]"))
        assertNull(RecipeList.parse("I think you need chicken"))
        assertNull(RecipeList.parse(null))
        val q = RecipeList.request("zorblax stew", Lang.FR)
        assertEquals(OneShotKind.RECIPE, q.kind)
        assertTrue(q.system.endsWith("in French."))
    }

    @Test fun theGuessLineSaysItIsAGuess() {
        assertEquals("A usual recipe for lemon chicken, so a guess. Untick what you have, then tap Add.", ListsStrings.recipeGuess("dish" to "lemon chicken").en())
        assertEquals("I don't know a usual recipe for zorblax stew. Share the recipe with me and I'll list its ingredients.",
            ListsStrings.recipeUnknown("dish" to "zorblax stew").en())
    }
}
