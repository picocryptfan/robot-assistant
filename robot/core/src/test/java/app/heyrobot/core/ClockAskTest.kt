// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 1.1.2: without the model, the time and a timer are still answered from your words (ClockAsk); nothing else is. */
class ClockAskTest {
    private val now = ZonedDateTime.of(2026, 10, 29, 19, 2, 0, 0, ZoneId.of("America/Halifax"))
    private val fr = Langs(Lang.FR, Lang.FR, Lang.FR)

    @Test fun theTimeIsSaidFromThePhonesClock() {
        for (q in listOf("What time is it?", "what's the time", "Hey Robot, what time is it", "what time is it now please", "Tell me the time.")) {
            val a = ClockAsk.route(q, now) as ClockAsk.Answer.Say
            assertEquals(q, Lang.EN, a.lang)
            assertEquals(q, "It's 7:02, Thursday October 29.", a.text.en())
        }
        for (q in listOf("Quelle heure est-il ?", "quelle heure il est", "Il est quelle heure?", "Robot, c'est quelle heure", "Dis-moi l'heure s'il te plaît")) {
            val a = ClockAsk.route(q, now) as ClockAsk.Answer.Say
            assertEquals(q, Lang.FR, a.lang)
            assertEquals(q, "Il est ${BriefClock.fr(now.toLocalTime())}, jeudi 29 octobre.", a.text.render(fr))
        }
    }

    @Test fun aTimerIsSetForTheLengthYouSaid() {
        val timers = mapOf("Set a timer for 10 minutes" to 600L, "hey robot set a timer for five minutes please" to 300L,
            "start a 20 minute timer" to 1200L, "timer for 30 seconds" to 30L, "Mets une minuterie de 10 minutes" to 600L,
            "mets une minuterie de dix minutes s'il te plaît" to 600L, "set a timer for 1 hour" to 3600L)
        val got = timers.keys.associateWith { q -> (ClockAsk.route(q, now) as? ClockAsk.Answer.Call)?.call }
        assertEquals(timers.mapValues { (_, s) -> RawToolCall(ToolName.SET_TIMER.id, mapOf("seconds" to s)) }, got)
    }

    @Test fun anythingElseIsLeftAlone() {
        for (q in listOf("", "set a timer", "set a timer for 10 minutes and text Sam", "what time does the store close", "time to go",
            "how long is left on my timer", "quelle heure ferme l'épicerie", "set an alarm for 7", "what's the weather", "timer")) {
            assertNull(q, ClockAsk.route(q, now))
        }
    }
}
