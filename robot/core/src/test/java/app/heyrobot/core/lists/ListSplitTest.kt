// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.BrainMsg
import app.heyrobot.core.DoneReason
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.OneShotRules
import app.heyrobot.core.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lists-plan.md §3.5 and §8.3 (LS4): the model's split of a run of your words is kept only when every item is a run of your
 * words, in order, together covering all of them but "and", "some" and their French; the items are then your words, never
 * the model's spelling. And add_to_list's card (§8.2, D6): items are yours only when each is a run of what you said.
 */
class ListSplitTest {
    @Test fun aSplitOfYourOwnWordsIsKept() {
        assertEquals(listOf("eggs", "oat milk", "bread"), ListSplit.accept("eggs oat milk bread", """["eggs", "oat milk", "bread"]"""))
        // Code fences and spaces around the array, as small models write them.
        assertEquals(listOf("eggs", "oat milk", "bread"), ListSplit.accept("eggs oat milk bread", "```json\n[\"eggs\",\"oat milk\",\"bread\"]\n```"))
        // One item covering it all: the run is one item.
        assertEquals(listOf("call the bank"), ListSplit.accept("call the bank", """["call the bank"]"""))
        // Your words, never the model's case or accents: « Œufs » stays as said.
        assertEquals(listOf("œufs", "lait d'avoine", "pain"), ListSplit.accept("œufs lait d'avoine pain", """["oeufs", "LAIT D'AVOINE", "pain"]"""))
        // A quantity stays with its item.
        assertEquals(listOf("2 litres of milk", "eggs", "bread"), ListSplit.accept("2 litres of milk eggs bread", """["2 litres of milk", "eggs", "bread"]"""))
    }

    @Test fun joiningWordsMayBeLeftOut() {
        assertEquals(listOf("eggs", "bread"), ListSplit.accept("some eggs and bread", """["eggs", "bread"]"""))
        assertEquals(listOf("eggs", "some bread"), ListSplit.accept("eggs and some bread", """["eggs", "some bread"]"""))
        assertEquals(listOf("œufs", "pain", "crème"), ListSplit.accept("des œufs du pain et de la crème", """["œufs", "pain", "crème"]"""))
        assertEquals(listOf("des œufs", "du pain"), ListSplit.accept("des œufs du pain", """["des œufs", "du pain"]"""))
    }

    @Test fun anInventedChangedDroppedOrReorderedWordIsRefused() {
        val run = "eggs oat milk bread"
        for (answer in listOf(
            """["eggs", "oat milk", "bread", "PIN 4821"]""",     // invented
            """["eggs", "almond milk", "bread"]""",             // changed
            """["eggs", "bread"]""",                           // dropped
            """["bread", "eggs", "oat milk"]""",               // reordered
            """["eggs", "oat", "oat milk", "bread"]""",        // a word twice
            """["eggs oat", "milk bread", "text Sam"]""",      // an instruction added
            """[]""", """["eggs", 3, "bread"]""", """["eggs", "", "bread"]""", """{"items": ["eggs"]}""",
            "eggs, oat milk, bread", "", "[\"eggs\", \"oat milk\"", "[\"eggs\", \"oat milk\", \"bread\"",
        )) assertNull(answer, ListSplit.accept(run, answer))
        assertNull(ListSplit.accept(run, null))
        // "de" alone is a word of an item (« pâte de tomates »), never a joining word left out.
        assertNull(ListSplit.accept("pâte de tomates riz", """["pâte", "tomates", "riz"]"""))
        // More than 20 items at once is refused, as code refuses them.
        val many = (1..21).joinToString(" ") { "thing$it" }
        assertNull(ListSplit.accept(many, (1..21).joinToString(",", "[", "]") { "\"thing$it\"" }))
    }

    @Test fun onlyARunOfThreeWordsOrMoreGoesToTheModel() {
        assertTrue(ListSplit.wants("eggs oat milk bread"))
        assertTrue(ListSplit.wants("2 litres of milk eggs bread"))
        assertTrue(ListSplit.wants("call the bank"))
        assertFalse(ListSplit.wants("oat milk"))
        // Its quantity and article off: two words.
        assertFalse(ListSplit.wants("some oat milk"))
        assertFalse(ListSplit.wants("2 litres of oat milk"))
        assertFalse(ListSplit.wants(""))
        assertFalse(ListSplit.wants("x ".repeat(100)))
    }

    @Test fun theOneShotIsFixedAndQuotesYourWords() {
        val r = ListSplit.request("eggs oat milk bread")
        assertEquals(OneShotKind.LIST_SPLIT, r.kind)
        assertEquals(Priority.PANEL, r.priority)
        assertEquals(ListSplit.SYSTEM, r.system)
        assertEquals("\"\"\"\neggs oat milk bread\n\"\"\"", r.text)
        assertFalse(r.hasImage)
        assertTrue(OneShotRules.fits(r, 2048))
        // Its conversation: no tools, chat only, whatever the model writes.
        assertTrue(OneShotRules.conversation(r).tools.isEmpty())
        // Only a finished answer is read.
        assertEquals("[\"a\"]", ListSplit.answer(OneShotResult.Done("[\"a\"]", DoneReason.END)))
        assertNull(ListSplit.answer(OneShotResult.Done("[\"a\", \"b", DoneReason.LENGTH)))
        assertNull(ListSplit.answer(OneShotResult.TimedOut("[\"a\"")))
        assertNull(ListSplit.answer(OneShotResult.NotStarted))
        assertTrue(BrainMsg.OneShot::class.isInstance(r))
    }

    @Test fun addToListsItemsAreYoursOnlyWhenYouSaidThem() {
        fun items(s: String) = (ItemText.read(s) as ItemsRead.Ok).items
        assertTrue(ListSplit.yours(items("oat milk, eggs"), "add oat milk and eggs to my list please"))
        assertTrue(ListSplit.yours(items("Oat-milk"), "we're out of oat milk"))
        assertTrue(ListSplit.yours(items("2 litres of milk"), "add two litres of milk"))
        assertTrue(ListSplit.yours(items("lait d'avoine"), "on n'a plus de lait d'avoine"))
        // Not your words: the model's own, or a screen's.
        assertFalse(ListSplit.yours(items("flour, eggs, butter"), "add what I need for pancakes"))
        assertFalse(ListSplit.yours(items("oat milk, eggs"), "add oat milk"))
        assertFalse(ListSplit.yours(items("milk oat"), "add oat milk"))
        assertFalse(ListSplit.yours(items("3 eggs"), "add eggs"))
        assertFalse(ListSplit.yours(items("eggs"), null))
        assertFalse(ListSplit.yours(items("eggs"), ""))
        assertFalse(ListSplit.yours(emptyList(), "eggs"))
    }
}
