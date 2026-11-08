// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.TurnRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix-french-turn-c: when the panel's conversation follows its newest turn. */
class PanelFollowTest {
    private fun you(id: String) = UiTurn(id, TurnRole.USER, "q")
    private fun robot(id: String) = UiTurn(id, TurnRole.ASSISTANT, "a")

    @Test fun theListsOwnScrollToACardsTopKeepsFollowing() {
        val f = PanelFollow()
        val turns = listOf(you("u1"), robot("r1"))
        assertTrue(f.newest(turns))
        f.topped(300)
        f.scrolled(0, 300)
        assertTrue(f.follow)
        // Your next turn and its reply, added at once: the list goes to them.
        assertTrue(f.newest(turns + you("u2") + robot("r2")))
    }

    @Test fun scrolledUpToReadAReplyAloneDoesntMoveTheList() {
        val f = PanelFollow()
        val turns = listOf(you("u1"), robot("r1"))
        f.newest(turns)
        f.scrolled(1, 40)
        assertFalse(f.newest(turns + robot("r2")))
        // Past where the list topped a card is yours too.
        f.topped(300)
        f.scrolled(0, 120)
        assertFalse(f.follow)
    }

    @Test fun aTurnOfYoursAlwaysFollowsEvenScrolledUp() {
        val f = PanelFollow()
        f.newest(listOf(you("u1"), robot("r1")))
        f.scrolled(2, 0)
        assertTrue(f.newest(listOf(you("u1"), robot("r1"), you("u2"), robot("r2"))))
        f.scrolled(3, 10)
        assertTrue(f.newest(listOf(you("u1"), robot("r1"), you("u2"), robot("r2"), you("u3"))))
    }

    @Test fun eachPathSendTakesIsLoggedByNameAndLanguageNeverTheWords() {
        val lines = mutableListOf<String>()
        app.heyrobot.core.RLog.sink = { lines += it }
        try {
            app.heyrobot.core.RLog.i("send_path", "path" to app.heyrobot.core.RLog.Code("offer_here"), "lang" to app.heyrobot.core.i18n.Lang.FR, "voice" to false)
        } finally { app.heyrobot.core.RLog.sink = null }
        assertTrue(lines.single(), lines.single().contains("send_path path=offer_here lang=FR voice=false"))
    }
}
