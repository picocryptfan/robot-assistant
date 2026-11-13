// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.later

import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.core.Card
import app.heyrobot.core.Executed
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.online.LaterItem
import app.heyrobot.core.online.LaterKeep
import app.heyrobot.core.online.LaterState
import app.heyrobot.core.online.LaterGate
import app.heyrobot.core.web.WebGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The panel's side of Signal later (review-signal-later): what is kept, with which ask-first rule, and never while off.
class PanelLaterTest {
    private val savedGate = PanelLater.gate
    private val savedKeeper = PanelLater.keeper
    private val savedOnline = PanelLater.onlineNow
    private val kept = mutableListOf<Boolean>()

    @Before fun seams() {
        PanelLater.keeper = { _, _, _, asks -> kept += asks; SignalLater.Kept.KEPT }
        PanelLater.onlineNow = { false }
    }

    @After fun restore() {
        PanelLater.gate = savedGate
        PanelLater.keeper = savedKeeper
        PanelLater.onlineNow = savedOnline
    }

    @Test fun webSearchOffKeepsNothingAndSaysHowToTurnItOn() {
        PanelLater.gate = { _, _ -> LaterGate.Off(WebGate.OFF_TURN_ON) }
        assertEquals(WebGate.OFF_TURN_ON, PanelLater.keepOffline("look up the ferry times", "ferry times", Lang.EN, untrusted = false, locked = false))
        assertEquals(WebGate.OFF_TURN_ON, PanelLater.keepNow("look up the ferry times", "ferry times", Lang.EN, untrusted = false))
        assertTrue(kept.isEmpty())
    }

    @Test fun keptWithItsAskFirstRule() {
        PanelLater.gate = { _, _ -> LaterGate.Goes }
        assertEquals(EverydayStrings.laterKept(), PanelLater.keepNow("look up the ferry times", "ferry times", Lang.EN, untrusted = false))
        PanelLater.keepNow("look up the ferry times", "ferry times", Lang.EN, untrusted = true)
        PanelLater.gate = { _, _ -> LaterGate.Asks }
        PanelLater.keepNow("look up the ferry times", "ferry times", Lang.EN, untrusted = false)
        assertEquals(listOf(false, true, true), kept)
    }

    // review-signal-later 3 (Low) and the wording: a crash mid-search reads "couldn't finish"; an expired one names no words.
    @Test fun whatTheNextChatShows() {
        val en = Langs.of(Lang.EN)
        val fr = Langs.of(Lang.FR)
        fun i(state: LaterState, answer: String?, lang: Lang = Lang.EN) = LaterItem("a", "Look up the ferry times", "ferry times", lang, 0, false, state, answer)
        PanelLater.shown(i(LaterState.ANSWERED, null)).render(en).let { assertTrue(it, it.endsWith("The search couldn't finish.")) }
        PanelLater.shown(i(LaterState.ANSWERED, "")).render(en).let { assertTrue(it, it.endsWith("The search found nothing.")) }
        assertTrue(PanelLater.shown(i(LaterState.ANSWERED, "Sailings at 9")).render(en).let { it.contains("ferry times") && it.endsWith("Sailings at 9") })
        val gone = PanelLater.shown(i(LaterState.EXPIRED, null)).render(en)
        assertTrue(gone, gone.startsWith("Your question from") && gone.endsWith("waited a day, so I dropped it."))
        PanelLater.shown(i(LaterState.ANSWERED, null, Lang.FR)).render(fr).let { assertTrue(it, it.endsWith("La recherche n’a pas pu finir.")) }
        EverydayStrings.laterKept().render(en).let { assertTrue(it, it.endsWith("if that's within a day.")) }
        EverydayStrings.laterKept().render(fr).let { assertTrue(it, it.contains("je garde ta question")) }
        assertEquals("Robot has news about your question", EverydayStrings.laterPublic().render(en))
    }

    // review-signal-later-c 4: the spoken "… when we're back in range" path says web search is off before "unlock".
    @Test fun lockedSpokenKeepWithWebSearchOffSaysItIsOff() {
        PanelLater.gate = { _, _ -> LaterGate.Off(WebGate.OFF_TURN_ON) }
        val texts = mutableListOf<String>()
        val host = object : PanelRoutines.Host {
            override val speak = false
            override val locked = true
            override fun text(replyId: String, text: String) { texts += text }
            override fun append(replyId: String, text: String) {}
            override fun say(sentence: String, personal: Boolean) {}
            override suspend fun ask(replyId: String, card: Card, tapOnly: String?) = false
            override fun show(replyId: String, card: Card) {}
            override suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed? = null
            override fun finish(replyId: String) {}
            override fun quoted(replyId: String) {}
            override suspend fun leave() {}
            override fun langs(said: String?, router: Lang?) = Langs.of(Lang.EN)
        }
        runBlocking { PanelLater.handle("r", LaterKeep(Lang.EN, "look up the ferry times", "ferry times"), host) }
        assertEquals(listOf(WebGate.OFF_TURN_ON.render(Langs.of(Lang.EN))), texts)
        assertTrue(kept.isEmpty())
    }

    @Test fun lockedWithWebSearchOffSaysItIsOff() {
        PanelLater.gate = { _, _ -> LaterGate.Off(WebGate.OFF_TURN_ON) }
        assertEquals(WebGate.OFF_TURN_ON, PanelLater.keepOffline("look up the ferry times", "ferry times", Lang.EN, untrusted = false, locked = true))
        assertTrue(kept.isEmpty())
    }

    @Test fun onlineOrLockedKeepsNothing() {
        PanelLater.gate = { _, _ -> LaterGate.Goes }
        assertEquals(EverydayStrings.laterUnlock(), PanelLater.keepOffline("look up the ferry times", "ferry times", Lang.EN, untrusted = false, locked = true))
        PanelLater.onlineNow = { true }
        assertNull(PanelLater.keepOffline("look up the ferry times", "ferry times", Lang.EN, untrusted = false, locked = false))
        assertTrue(kept.isEmpty())
    }
}
