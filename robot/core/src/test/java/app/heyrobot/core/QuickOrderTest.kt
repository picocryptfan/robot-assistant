// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** HS2: Quick access's order, by drag and by "put car mode first" « mets le mode voiture en premier ». */
class QuickOrderTest {
    private val d = QuickItem.DEFAULT

    @Test fun movesFollowTodayArrangesRules() {
        assertEquals(QuickItem.CAR, QuickOrder.moved(d, QuickItem.CAR, TodayMove.FIRST).first())
        assertEquals(QuickItem.TALK, QuickOrder.moved(d, QuickItem.TALK, TodayMove.LAST).last())
        assertEquals(d, QuickOrder.moved(d, QuickItem.TALK, TodayMove.UP))
        assertEquals(listOf(QuickItem.CAR, QuickItem.TALK), QuickOrder.moved(d, QuickItem.TALK, TodayMove.DOWN).take(2))
        assertEquals(QuickItem.SKILLS, QuickOrder.dragged(d, QuickItem.SKILLS, -3).first())
        // Robot to Robot isn't shown here: Skills moves up past it, onto Car mode's place.
        val shown = d - QuickItem.LINK
        assertEquals(listOf(QuickItem.TALK, QuickItem.SKILLS, QuickItem.CAR, QuickItem.LINK), QuickOrder.moved(d, QuickItem.SKILLS, TodayMove.UP, shown).take(4))
        assertEquals(listOf(QuickItem.SKILLS, QuickItem.TALK), QuickOrder.placed(d, shown, QuickItem.SKILLS, 0).take(2))
    }

    @Test fun englishAndFrenchPhrases() {
        for ((s, want) in listOf(
            "put car mode first" to (QuickItem.CAR to TodayMove.FIRST),
            "Put car mode first." to (QuickItem.CAR to TodayMove.FIRST),
            "move skills to the top on quick access" to (QuickItem.SKILLS to TodayMove.FIRST),
            "put the interpreter last" to (QuickItem.INTERPRETER to TodayMove.LAST),
            "Move Robot to Robot down" to (QuickItem.LINK to TodayMove.DOWN),
            "Mets le mode voiture en premier" to (QuickItem.CAR to TodayMove.FIRST),
            "mets le mode auto en premier" to (QuickItem.CAR to TodayMove.FIRST),
            "Mets l'interprète en dernier" to (QuickItem.INTERPRETER to TodayMove.LAST),
            "remonte l’historique" to (QuickItem.HISTORY to TodayMove.UP),
            "descends mes compétences d'un cran" to (QuickItem.SKILLS to TodayMove.DOWN),
        )) assertEquals(s, want, QuickArrange.match(s))
    }

    @Test fun onlyWholeUtterances() {
        assertNull(QuickArrange.match("the note says put car mode first"))
        assertNull(QuickArrange.match("put car mode first and call mom"))
        assertNull(QuickArrange.match("put car mode"))
    }
}
