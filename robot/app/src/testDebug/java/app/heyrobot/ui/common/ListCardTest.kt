// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.assistant.PanelLists
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ListsStrings
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.sys.ListsVault
import app.heyrobot.sys.SoftListsKeys
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * LS5 (lists-plan.md §7.4): the panel's list card: open items with tick boxes, each row a whole 48 dp target, "Done (n)"
 * collapsed until tapped, Undo once a tap changed something, and "Open Shopping"; History's copy draws no buttons. Goldens
 * in English and Québec French, light and dark, in the panel's type.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ListCardTest {
    @get:Rule val rule = createComposeRule()
    private val ops = ArrayList<ListCardOp>()
    private val shopping = ListCardView("0".repeat(31) + "1", "Shopping",
        open = listOf(ListCardRow("a".repeat(32), "Oat milk", "2 L"), ListCardRow("b".repeat(32), "Eggs"), ListCardRow("c".repeat(32), "Bread")),
        done = listOf(ListCardRow("d".repeat(32), "Coffee")), label = ListsStrings.shopping())

    @Test fun ticksDoneUndoAndOpen() {
        rule.show(classic = true) { ListCard(shopping, onOp = { ops += it }) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("list_card")
        rule.onNodeWithText("Eggs").performClick()
        rule.onNodeWithText("Coffee").assertDoesNotExist()
        rule.onNodeWithText("Done (1)").performClick()
        rule.onNodeWithText("Coffee").performClick()
        rule.onNodeWithText("Open Shopping").performClick()
        rule.onNodeWithText("Undo").assertDoesNotExist()
        assertEquals(listOf(ListCardOp.Tick("b".repeat(32), true), ListCardOp.Tick("d".repeat(32), false), ListCardOp.Open), ops)
    }

    @Test fun afterATickItSaysWhatAndOffersUndo() {
        val ticked = shopping.copy(open = shopping.open.filter { it.text != "Eggs" }, done = shopping.done + ListCardRow("b".repeat(32), "Eggs"),
            line = "Ticked off eggs.", undo = true)
        rule.show(classic = true) { ListCard(ticked, onOp = { ops += it }) }
        rule.onNodeWithText("Ticked off eggs.").assertExists()
        rule.onNodeWithText("Done (2)").assertExists()
        rule.shot("list_card_ticked")
        rule.onNodeWithText("Undo").performClick()
        assertEquals(listOf<ListCardOp>(ListCardOp.Undo), ops)
    }

    @Test fun withoutActionsNoButtons() {
        rule.show(classic = true) { ListCard(shopping, onOp = null) }
        rule.onNodeWithText("Open Shopping").assertDoesNotExist()
        rule.onNodeWithText("Eggs").performClick()
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun inFrench() {
        rule.show(classic = true) { ListCard(shopping.copy(name = "Épicerie"), onOp = { ops += it }) }
        rule.onNodeWithText("Épicerie").assertExists()
        rule.onNodeWithText("Fait (1)").assertExists()
        rule.onNodeWithText("Ouvrir la liste Épicerie").assertExists()
        rule.shot("list_card_fr")
    }

    /**
     * LS13 (LS11c on the Pixel 8): with a reply in French and the menus in English, the add and tick cards' button said "Open
     * Shopping" (the menus' language) and the read card's "Open Épicerie" (the reply's). A default list has one name on a
     * button, the menus'; the card's title stays in the reply's language, as an add card's does (l10n-1.1-design §7.2).
     */
    @Test fun aReadCardsButtonNamesTheListInTheMenusLanguage() {
        val dir = File("build/tmp/ls13-card").apply { deleteRecursively(); mkdirs() }
        val vault = ListsVault(File(dir, "lists"), SoftListsKeys(), settings = { ListsSettings() }, someoneElse = { false }, locked = { false })
        PanelLists.env = object : PanelLists.Env {
            override fun vault() = vault
            override fun settings() = ListsSettings()
            override fun locked() = false
            override fun someoneElse() = false
        }
        PanelLists.forget()
        try {
            val ctx = RuntimeEnvironment.getApplication()
            val fr = Langs.of(Lang.FR)
            var n = 0
            fun say(t: String) = PanelLists.handle(ctx, "reply${n++}", PanelLists.route(ctx, t)!!, fr, spoken = true)
            say("ajoute des œufs à ma liste d'épicerie")
            val read = say("qu'est-ce qu'il y a sur ma liste d'épicerie").list!!
            rule.show(classic = true) { ListCard(read, onOp = { ops += it }) }
            rule.onNodeWithText("Épicerie").assertExists()
            rule.onNodeWithText("Open Shopping").performClick()
            assertEquals(listOf<ListCardOp>(ListCardOp.Open), ops)
        } finally {
            PanelLists.forget()
            PanelLists.env = null
            dir.deleteRecursively()
        }
    }

    @Test fun dark() {
        rule.show(dark = true, classic = true) { ListCard(shopping, onOp = { ops += it }) }
        rule.shot("list_card_dark")
    }
}
