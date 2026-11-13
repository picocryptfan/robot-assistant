// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListsCommand
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.sys.ListsVault
import app.heyrobot.sys.SoftListsKeys
import app.heyrobot.ui.common.ListCardOp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** recipe-list: a recipe's ingredients onto Robot's list: one card, each row ticked, "not the oil", and nothing added before Add. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelRecipeListTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val ctx = RuntimeEnvironment.getApplication()
    private var lockedNow = false
    private val keys = SoftListsKeys()
    private val vault by lazy { ListsVault(File(tmp.root, "lists"), keys, settings = { ListsSettings() }, someoneElse = { false }, locked = { lockedNow }) }
    private var n = 0
    private var answer: String? = null
    private val asked = ArrayList<Pair<String, Lang>>()
    private val frL = Langs.of(Lang.FR)

    @Before fun phone() {
        PanelLists.env = object : PanelLists.Env {
            override fun vault(): ListsVault = this@PanelRecipeListTest.vault
            override fun settings(): ListsSettings = ListsSettings()
            override fun locked(): Boolean = lockedNow
            override fun someoneElse(): Boolean = false
            override fun recipe(dish: String, lang: Lang): String? = answer.also { asked += dish to lang }
        }
        PanelLists.forget()
    }

    @After fun clean() {
        PanelLists.forget()
        PanelLists.env = null
    }

    private fun say(t: String, langs: Langs = Langs.EN, shared: String? = null): PanelLists.Reply? {
        val c = PanelLists.route(ctx, t, shared) ?: return null
        return PanelLists.handle(ctx, "reply${n++}", c, langs, spoken = true)
    }

    private fun items(): List<String> = when (val l = vault.load()) {
        is ListsLoad.Loaded -> l.value.withRole(ListRole.SHOPPING)?.open?.map { it.text }.orEmpty()
        else -> emptyList()
    }

    private fun plain(s: String) = s.replace(' ', ' ').replace(' ', ' ').replace('’', '\'')

    @Test fun aGuessThenNotTheOilThenAdd() {
        answer = """["chicken thighs", "lemons", "olive oil", "garlic"]"""
        val r = say("Add what I need for lemon chicken to the grocery list")!!
        assertEquals("A usual recipe for lemon chicken, so a guess. Untick what you have, then tap Add.", r.text.render(Langs.EN))
        assertEquals(listOf("lemon chicken" to Lang.EN), asked)
        val card = r.list!!
        assertEquals(listOf("Chicken thighs", "Lemons", "Olive oil", "Garlic"), card.open.map { it.text })
        assertEquals(4, card.picks!!.size)
        assertTrue("nothing added before the tap", items().isEmpty())

        val d = say("Not the oil, I have it.")!!
        assertEquals("Unticked Olive oil. Tap Add when it's right.", d.text.render(Langs.EN))
        assertEquals(setOf("0", "1", "3"), d.list!!.picks)
        assertTrue(items().isEmpty())

        val after = PanelLists.cardTap(ctx, "reply1", d.list!!, ListCardOp.Add, Langs.EN)
        assertNull(after.picks)
        assertEquals(listOf("chicken thighs", "lemons", "garlic"), items().map { it.lowercase() })
    }

    @Test fun aTapUnticksARow() {
        answer = """["flour", "eggs", "milk"]"""
        val card = say("add the ingredients for pancakes to my shopping list")!!.list!!
        val t = PanelLists.cardTap(ctx, "reply0", card, ListCardOp.Tick("1", done = false), Langs.EN)
        assertEquals(setOf("0", "2"), t.picks)
        PanelLists.cardTap(ctx, "reply0", t, ListCardOp.Add, Langs.EN)
        assertEquals(listOf("flour", "milk"), items().map { it.lowercase() })
    }

    @Test fun inFrench() {
        answer = """["cuisses de poulet", "citrons", "huile d'olive", "ail"]"""
        val r = say("Ajoute ce qu'il me faut pour un poulet au citron à la liste d'épicerie, mais pas l'huile", frL)!!
        assertEquals(listOf("poulet au citron" to Lang.FR), asked)
        assertEquals("Une recette habituelle de poulet au citron, donc une supposition. Décoche ce que tu as, puis touche Ajouter. " +
            "Décoché : Huile d'olive. Touche Ajouter quand c'est bon.", plain(r.text.render(frL)))
        assertEquals(setOf("0", "1", "3"), r.list!!.picks)
    }

    @Test fun anUnknownDish() {
        answer = "[]"
        val r = say("add what I need for zorblax stew to my grocery list")!!
        assertEquals("I don't know a usual recipe for zorblax stew. Share the recipe with me and I'll list its ingredients.", r.text.render(Langs.EN))
        assertNull(r.list)
        assertTrue(items().isEmpty())
        answer = null
        assertNull(say("add what I need for soup to my grocery list")!!.list)
    }

    @Test fun aSharedRecipesOwnWords() {
        val text = "Lemon chicken\nIngredients\n- 4 chicken thighs\n- 2 lemons\n- 1 tbsp olive oil\nMethod\nRoast it."
        val r = say("add the ingredients to my shopping list", shared = text)!!
        assertEquals("The ingredients from the recipe you shared. Untick what you have, then tap Add.", r.text.render(Langs.EN))
        assertTrue("the model isn't asked", asked.isEmpty())
        assertEquals(3, r.list!!.open.size)
        PanelLists.cardTap(ctx, "reply0", r.list!!, ListCardOp.Add, Langs.EN)
        assertEquals(3, items().size)
    }

    @Test fun theIngredientsAloneStayTheModels() {
        assertFalse(PanelLists.route(ctx, "add the ingredients to my shopping list") is ListsCommand.Recipe)
        assertNull(say("not the oil"))
    }

    @Test fun theCardGoesWhenThePanelHides() {
        answer = """["flour", "eggs", "milk"]"""
        val card = say("add the ingredients for pancakes to my shopping list")!!.list!!
        PanelLists.drop() // PanelController.onHide; showing the panel again brings nothing back
        val after = PanelLists.cardTap(ctx, "reply0", card, ListCardOp.Add, Langs.EN)
        assertEquals(app.heyrobot.core.i18n.ListsStrings.notNow().render(Langs.EN), after.line)
        assertTrue("nothing added from a card that went with the panel", items().isEmpty())
    }

    @Test fun lockedNoCard() {
        lockedNow = true
        keys.locked = true
        answer = """["eggs"]"""
        val r = say("add what I need for an omelette to the grocery list")!!
        assertNull(r.list)
        assertTrue(asked.isEmpty())
    }
}
