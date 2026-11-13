// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Prepared
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.web.WebGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaterTest {
    private fun item(id: String, at: Long = 0, said: String = "ferry times to Digby", asks: Boolean = false, state: LaterState = LaterState.WAITING) =
        LaterItem(id, said, said, Lang.EN, at, asks, state)

    @Test fun keepsTheFerryQuestionWithItsCapitals() {
        val k = LaterRouter.route("No signal out here. Look up the ferry times to Digby when we're back in range.") as LaterKeep
        assertEquals(Lang.EN, k.lang)
        assertEquals("ferry times to digby", OnlineRouter.plain(k.query).removePrefix("the "))
        assertEquals("Look up the ferry times to Digby", k.said)
    }

    // Spoken first: what the recognizer hands the panel, the headset session and the spoken runner (all through send) is
    // kept as a typed ask is, apostrophe or none, no capitals, no full stop.
    @Test fun aSpokenAskIsKept() {
        for (q in listOf("look up the ferry times when we're back in range", "look up the ferry times when were back in range")) {
            val k = LaterRouter.route(q)
            assertTrue(q, k is LaterKeep)
            assertTrue(q, (k as LaterKeep).query.contains("ferry times"))
        }
    }

    @Test fun otherTails() {
        assertTrue(LaterRouter.route("search for the tide table in Yarmouth once there's signal again") is LaterKeep)
        assertTrue(LaterRouter.route("check the hockey score as soon as I'm back online") is LaterKeep)
    }

    @Test fun french() {
        val k = LaterRouter.route("Pas de réseau ici. Cherche les horaires du traversier pour Digby quand on aura du réseau.") as LaterKeep
        assertEquals(Lang.FR, k.lang)
        assertEquals("les horaires du traversier pour digby", k.query)
        assertTrue(LaterRouter.route("cherche la météo de Halifax dès qu'il y aura une connexion") is LaterKeep)
        assertEquals(LaterList(Lang.FR), LaterRouter.route("Qu'est-ce qui attend ?"))
        assertEquals(LaterCancel(Lang.FR, "traversier", all = false), LaterRouter.route("annule la question sur traversier"))
        assertEquals(LaterCancel(Lang.FR, null, all = true), LaterRouter.route("annule les questions en attente"))
    }

    @Test fun listAndCancel() {
        assertEquals(LaterList(Lang.EN), LaterRouter.route("What's waiting?"))
        assertEquals(LaterCancel(Lang.EN, "ferry", all = false), LaterRouter.route("cancel the ferry one"))
        assertEquals(LaterCancel(Lang.EN, null, all = true), LaterRouter.route("cancel all the waiting questions"))
    }

    @Test fun offlineQuestionsAreNeverTaken() {
        listOf("what time is it", "set a timer for 5 minutes", "what went online today", "cancel the alarm", "remind me when I get home",
            "call mom when we're back in range", "is the internet down").forEach { q ->
            assertFalse(q, LaterRouter.route(q) is LaterKeep)
        }
        assertNull(LaterRouter.route("what time is it"))
        assertNull(LaterRouter.route("set a timer for 5 minutes"))
        assertNull(LaterRouter.route("cancel the alarm"))
    }

    // review-signal-later 2: only a look-up is kept; "call mom when we're back in range" is never a search.
    @Test fun onlyALookUpIsKept() {
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("call mom when we're back in range"))
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("No signal here. Text Sam I'm late once there's signal"))
        assertEquals(LaterCant(Lang.FR), LaterRouter.route("appelle maman quand on aura du réseau"))
        assertEquals(LaterCant(Lang.FR), LaterRouter.route("Pas de réseau ici. Envoie un texto à Sam dès qu'il y aura une connexion"))
        assertTrue(LaterRouter.route("find out the ferry times when we're back in range") is LaterKeep)
        assertEquals("ferry times to digby", (LaterRouter.route("what are the ferry times to Digby when we're back online") as LaterKeep).query.removePrefix("what are the "))
        assertTrue(LaterRouter.route("cherche les horaires du traversier quand on aura du réseau") is LaterKeep)
        assertTrue(LaterRouter.route("quels sont les horaires du traversier quand on aura du réseau") is LaterKeep)
    }

    @Test fun aCommandOrAQuestionThePhoneAnswersIsNotKept() {
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("do the transfer to Sam when we're back in range"))
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("what time is it when we're back online"))
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("when is my next alarm when we're back in range"))
        assertEquals(LaterCant(Lang.FR), LaterRouter.route("quelle heure est-il quand on aura du réseau"))
        assertEquals(LaterCant(Lang.FR), LaterRouter.route("est-ce que mon alarme sonne demain quand on aura du réseau"))
        assertEquals(LaterCant(Lang.FR), LaterRouter.route("fais le virement à Sam quand on aura du réseau"))
    }

    @Test fun aQuestionWithItsSubjectIsKept() {
        assertTrue(LaterRouter.route("does the ferry leave at nine when we're back in range") is LaterKeep)
        assertTrue(LaterRouter.route("do they sell tickets on board when we're back online") is LaterKeep)
        assertTrue(LaterRouter.route("what time does the ferry leave when we're back in range") is LaterKeep)
        assertTrue(LaterRouter.route("à quelle heure part le traversier quand on aura du réseau") is LaterKeep)
        assertTrue(LaterRouter.route("est-ce que le traversier part à neuf heures quand on aura du réseau") is LaterKeep)
    }

    // review-signal-later-c 3: a named or plural subject then a common verb is a question; a chore is not.
    @Test fun aShortSubjectThenAVerbIsAQuestion() {
        assertTrue(LaterRouter.route("does Costco open at 9 when we're back in range") is LaterKeep)
        assertTrue(LaterRouter.route("do the ferries run Sunday when we're back online") is LaterKeep)
        assertTrue(LaterRouter.route("does Air Canada fly to Digby when we're back in range") is LaterKeep)
        assertTrue(LaterRouter.route("est-ce que Costco ouvre à 9 h quand on aura du réseau") is LaterKeep)
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("do the dishes when we're back in range"))
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("do my alarm when we're back in range"))
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("does my alarm ring at 7 when we're back in range"))
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("do the transfer to Sam when we're back in range"))
    }

    // review-signal-later-c 2: a refusal says how to keep it after all; "look up" and « cherche » are kept.
    @Test fun theRefusalSaysHowToKeepIt() {
        assertTrue(EverydayStrings.laterCant().render(Langs.of(Lang.EN)).contains("start with “look up”"))
        assertTrue(EverydayStrings.laterCant().render(Langs.of(Lang.FR)).contains("commence par"))
        assertTrue(EverydayStrings.laterCant().render(Langs.of(Lang.FR)).contains("cherche"))
        assertTrue(LaterRouter.route("look up do the ferries run on Sunday when we're back in range") is LaterKeep)
        assertTrue(LaterRouter.route("cherche les heures du traversier quand on aura du réseau") is LaterKeep)
    }

    // review-signal-later-c 1: "my" marks the phone's own things only, not any question with "my" in it.
    @Test fun myBeforeSomethingNotThePhonesIsKept() {
        assertTrue(LaterRouter.route("when does my flight leave when we're back in range") is LaterKeep)
        assertTrue(LaterRouter.route("est-ce que ma pharmacie ouvre dimanche quand on aura du réseau") is LaterKeep)
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("what's on my calendar tomorrow when we're back in range"))
        assertEquals(LaterCant(Lang.EN), LaterRouter.route("who is in my contacts when we're back online"))
        assertEquals(LaterCant(Lang.FR), LaterRouter.route("quand est mon rendez-vous dans mon calendrier quand on aura du réseau"))
    }

    @Test fun capOfFive() {
        var items = emptyList<LaterItem>()
        repeat(Later.CAP) { n -> items = (Later.add(items, item("q$n")) as Later.Add.Kept).items }
        assertEquals(Later.Add.Full, Later.add(items, item("q9")))
        // An answered one doesn't count against the cap.
        val one = Later.settle(items, "q0", LaterState.ANSWERED, "Sailings at 9")
        assertTrue(Later.add(one, item("q9")) is Later.Add.Kept)
    }

    @Test fun expiresAfterADayByTheWallClock() {
        val items = listOf(item("old", at = 0), item("new", at = Later.WAIT_MS))
        val after = Later.expire(items, now = Later.WAIT_MS + 1)
        assertEquals(LaterState.EXPIRED, after[0].state)
        assertEquals(LaterState.WAITING, after[1].state)
        assertEquals(listOf("old"), Later.toShow(after).map { it.id })
        assertEquals("new", Later.next(after)?.id)
    }

    // review-signal-later 1 (Low): one waiting for your OK expires too, and an expired one keeps nothing but its time.
    @Test fun aReadyOneExpiresAndAnExpiredOneKeepsNoWords() {
        val after = Later.expire(listOf(item("ok", at = 0, state = LaterState.READY), item("w", at = 0)), now = Later.WAIT_MS)
        assertEquals(listOf(LaterState.EXPIRED, LaterState.EXPIRED), after.map { it.state })
        assertTrue(after.all { it.said.isEmpty() && it.query.isEmpty() && it.answer == null && it.askedAt == 0L })
        assertFalse(Later.toJson(after).contains("ferry"))
        assertEquals(LaterState.ANSWERED, Later.expire(listOf(item("a", state = LaterState.ANSWERED)), now = Later.WAIT_MS).single().state)
    }

    @Test fun runsOnceOldestFirst() {
        var items = listOf(item("b", at = 20), item("a", at = 10))
        val first = Later.next(items)!!
        assertEquals("a", first.id)
        items = Later.settle(items, first.id, LaterState.ANSWERED, "x")
        assertEquals("b", Later.next(items)?.id)
        items = Later.settle(items, "b", LaterState.ANSWERED, "y")
        assertNull(Later.next(items))
    }

    @Test fun askFirstIsKept() {
        assertTrue(Later.mustAsk(item("a", asks = true), asksNow = false))
        assertTrue(Later.mustAsk(item("a", asks = false), asksNow = true))
        assertFalse(Later.mustAsk(item("a", asks = false), asksNow = false))
        val ready = Later.settle(listOf(item("a", asks = true)), "a", LaterState.READY)
        assertNull(Later.next(ready))
        assertEquals(Later.Send(setOf("a"), ready.single()), Later.toSend(ready) { LaterGate.Asks })
    }

    // review-signal-later 2: web search off or no service keeps nothing and sends nothing; it says how to turn it on.
    @Test fun webSearchOffIsOffNotAsks() {
        assertEquals(LaterGate.Off(WebGate.OFF_TURN_ON), LaterGate.of(Prepared.Refused(ActionStatus.DISABLED, "off")))
        assertEquals(LaterGate.Asks, LaterGate.of(Prepared.Refused(ActionStatus.FAILED, "daily cap")))
        assertEquals(LaterGate.Goes, LaterGate.of(Prepared.Ready(Action.WebSearch("ferry times"))))
        assertEquals(LaterGate.Asks, LaterGate.of(Prepared.Ready(Action.WebSearch("ferry times"), setOf(AskWhy.SEARCH))))
    }

    @Test fun aReadyQuestionIsDroppedNotSentWhileWebSearchIsOff() {
        val ready = listOf(item("a", at = 1, state = LaterState.READY), item("b", at = 2, state = LaterState.READY), item("w"))
        assertEquals(Later.Send(setOf("a", "b"), null), Later.toSend(ready) { LaterGate.Off(WebGate.OFF_TURN_ON) })
        assertEquals(Later.Send(setOf("a"), ready[0]), Later.toSend(ready) { LaterGate.Goes })
        assertEquals(Later.Send(emptySet(), null), Later.toSend(listOf(item("w"))) { LaterGate.Goes })
    }

    @Test fun cancelMatchesItsWords() {
        val items = listOf(item("f", said = "Look up the ferry times to Digby"), item("t", said = "tide table in Yarmouth"))
        assertEquals(listOf("f"), Later.matching(items, "ferry", all = false).map { it.id })
        assertEquals(2, Later.matching(items, null, all = true).size)
        assertTrue(Later.matching(items, "the one", all = false).isEmpty())
    }

    @Test fun jsonRoundTripsAndSkipsWhatItCantRead() {
        val items = listOf(item("a", at = 5, asks = true), item("b", state = LaterState.ANSWERED).copy(answer = "Sailings at 9", lang = Lang.FR))
        assertEquals(items, Later.fromJson(Later.toJson(items)))
        assertEquals(emptyList<LaterItem>(), Later.fromJson("not json"))
        assertEquals(1, Later.fromJson("""{"v":1,"items":[{"id":"a","state":"waiting"},{"id":"b","state":"??"}]}""").size)
        // An entry without its ask-first flag asks (fail closed).
        assertTrue(Later.fromJson("""{"v":1,"items":[{"id":"a","state":"waiting"}]}""").single().asks)
    }
}
