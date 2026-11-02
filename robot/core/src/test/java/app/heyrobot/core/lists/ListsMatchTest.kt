// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LS12 (LT3 on the Pixel 8): which items your words name, by a rule a person would expect. "is milk on my list" found
 * Milk thistle on the Pharmacy list. Now a word you say alone names an item only as its main word: the last in English
 * ("oat milk" is milk, "milk thistle" and "milk chocolate" aren't), the first in French (« lait d'avoine » is « lait »,
 * « café au lait » isn't). Two or more words name any item that holds them in a row ("call mom" finds "call mom about
 * Sunday"). The same item, or the same but for a plural, comes first as before.
 */
class ListsMatchTest {
    private fun id(n: Int): ListsId = ListsId.of("%032x".format(n))!!
    private fun item(n: Int, text: String, done: Boolean = false) = ListItem(id(100 + n), text, done = done, pos = n * 1000L, at = 0, upd = 0, by = ItemBy.YOU, how = ItemHow.VOICE)
    private fun find(items: List<ListItem>, said: String, lang: Lang = Lang.EN) = ListsMatch.find(items, said, lang).map { it.text }

    @Test fun aWordAloneIsTheItemsMainWordInEnglish() {
        val items = listOf(item(1, "Milk thistle"), item(2, "Oat milk"), item(3, "Milk chocolate"), item(4, "Bread flour"), item(5, "Free range eggs"))
        assertEquals(listOf("Oat milk"), find(items, "milk"))
        assertEquals(listOf("Free range eggs"), find(items, "eggs"))
        assertEquals(listOf("Free range eggs"), find(items, "the eggs"))
        assertEquals(emptyList<String>(), find(items, "bread"))
        assertEquals(emptyList<String>(), find(items, "thistle chocolate"))
        // The same item first: "Milk" itself beats Oat milk.
        assertEquals(listOf("Milk"), find(items + item(6, "Milk"), "milk"))
        assertEquals(listOf("Milk thistle"), find(items, "milk thistle"))
        // A number or a size after the main word doesn't change it: "Milk 2%" is milk.
        assertEquals(listOf("Milk 2%"), find(listOf(item(7, "Milk 2%"), item(8, "Milk thistle")), "milk"))
    }

    @Test fun aWordAloneIsTheItemsMainWordInFrench() {
        val items = listOf(item(1, "lait d'avoine"), item(2, "café au lait"), item(3, "miettes de pain"), item(4, "pain tranché"))
        assertEquals(listOf("lait d'avoine"), find(items, "le lait", Lang.FR))
        assertEquals(listOf("pain tranché"), find(items, "du pain", Lang.FR))
        assertEquals(listOf("café au lait"), find(items, "café", Lang.FR))
        // The English rule on French words would find the wrong ones.
        assertEquals(listOf("café au lait"), find(items, "lait", Lang.EN))
    }

    @Test fun twoWordsInARowFindAnItemThatHoldsThem() {
        val todo = listOf(item(1, "call mom about Sunday"), item(2, "book the dentist"))
        assertEquals(listOf("call mom about Sunday"), find(todo, "call mom"))
        assertEquals(listOf("book the dentist"), find(todo, "the dentist"))
        assertEquals(emptyList<String>(), find(todo, "call"))
        assertEquals(emptyList<String>(), find(todo, "mom call"))
        assertEquals(listOf("appeler maman dimanche"), find(listOf(item(3, "appeler maman dimanche")), "appeler maman", Lang.FR))
    }

    @Test fun theRouterHearsABareTickByTheSameRule() {
        val l = ListsList(id(1), role = ListRole.SHOPPING, made = 0, items = listOf(item(2, "Milk thistle"), item(3, "lait d'avoine")))
        val seen = ListsSeen.of(ListsFile(listOf(l)), null)
        assertFalse(seen.has("milk", Lang.EN))
        assertTrue(seen.has("milk thistle", Lang.EN))
        assertTrue(seen.has("le lait", Lang.FR))
        assertFalse(seen.has("le lait", Lang.EN))
        // "take milk off" with only Milk thistle on your lists isn't Lists' (it goes on down the chain).
        assertEquals(null, ListsRouter.route("take milk off", ListsHeard(seen = seen)))
        assertEquals(ListsCommand.Tick("le lait", ListRef.Unnamed, Lang.FR), ListsRouter.route("enlève le lait", ListsHeard(seen = seen)))
    }
}
