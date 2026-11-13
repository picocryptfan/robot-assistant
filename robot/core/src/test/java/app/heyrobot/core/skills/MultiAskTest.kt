// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class MultiAskTest {
    private val now = MultiBaselineTest.NOW

    private fun kinds(s: String, lists: Boolean = true): String =
        MultiAsk.split(s, now, lists)?.joinToString(",") { it.kind.name.lowercase() } ?: "-"

    @Test fun corpusSplitsAsWritten() {
        val rows = MultiBaselineTest.corpus()
        assertEquals(rows.map { it.said + " -> " + it.expect }, rows.map { it.said + " -> " + kinds(it.said) })
    }

    @Test fun theExampleIsThreeCallsInYourWords() {
        val p = MultiAsk.split("Add eggs and butter to the shopping list, set a 20-minute timer, and remind me at 6 to call Mom", now)!!
        assertEquals(mapOf("items" to "eggs and butter"), p[0].call.args)
        assertEquals("add_to_list", p[0].call.name)
        assertEquals(1200L, (p[1].call.args["seconds"] as Number).toLong())
        assertEquals("add_event", p[2].call.name)
        assertEquals(true, p[2].call.args["alert"])
        assertEquals("Call Mom", p[2].call.args["title"])
        assertEquals(listOf("Add eggs and butter to the shopping list", "set a 20-minute timer", "remind me at 6 to call Mom"), p.map { it.said })
    }

    @Test fun messagesCallsMoneyAndSecurityNeverSplit() {
        for (s in listOf("text Sam and Kim", "call Sam and set a timer for 5 minutes", "send Kim a message and set a timer for 5 minutes",
            "transfer 20 dollars to Sam and set a timer for 5 minutes", "add my bank PIN to the shopping list and set a timer for 5 minutes",
            "texte Sam et Kim", "virement à Sam et mets une minuterie de 5 minutes")) assertNull(s, MultiAsk.split(s, now))
    }

    @Test fun listsOffKeepsListsOnTheirPath() {
        assertEquals("-", kinds("set a timer for 10 minutes and add milk to the shopping list", lists = false))
    }

    @Test fun aBareHourIsTheNextTimeTheClockShowsIt() {
        assertEquals(LocalTime.of(18, 0), MultiAsk.clock("6", "", "", now))
        assertEquals(LocalTime.of(1, 0), MultiAsk.clock("1", "", "", now))
        assertEquals(LocalTime.of(18, 30), MultiAsk.clock("18", "30", "", now))
        assertEquals(LocalTime.of(7, 0), MultiAsk.clock("7", "", "a.m.", now))
        assertNull(MultiAsk.clock("25", "", "", now))
    }

    @Test fun languageOfTheCard() {
        assertEquals(Lang.FR, MultiAsk.lang("mets une minuterie de 10 minutes et ajoute du lait à la liste d'épicerie"))
        assertEquals(Lang.EN, MultiAsk.lang("set a timer for 10 minutes and add milk to the shopping list"))
    }
}
