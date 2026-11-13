// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.ContactName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** review-wikipedia-p4-d (Lows): a famous person's birthday reaches the copy; Québécois « la fête à Pierre » stays off. */
class WikiNameLowsTest {
    private val people = object : WikiTurn.Own {
        override fun names() = listOf(ContactName("c:1", "Pierre Gagnon"), ContactName("c:2", "Mark Chen"))
        override fun recalls(question: String) = false
    }
    private fun asks(q: String) = WikiTurn.asks(q, 1, attached = false, helperMode = false, untrusted = false, own = people)

    @Test fun aFamousBirthdayReachesTheCopy() {
        assertTrue(asks("When is Mark Twain's birthday?"))
        for (q in listOf("When is Mark's birthday?", "When is Mark Chen's birthday?", "When is Jordan's birthday?")) assertFalse(q, asks(q))
    }

    @Test fun feteAndAJoinANameToItsContactWord() {
        for (q in listOf("C'est quand la fête à Pierre?", "Quel est le cell à Pierre?", "C'est quoi le numéro à Pierre?")) assertFalse(q, asks(q))
        for (q in listOf("Qu'est-ce que la pierre de Rosette?", "Qui était Pierre Trudeau?")) assertTrue(q, asks(q))
    }
}
