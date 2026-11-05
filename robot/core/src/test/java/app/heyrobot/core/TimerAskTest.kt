// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * fix-everyday-e (everyday-1.2.2-a, finding 4): "set a timer for 1 minute", then "how much time is left on my timer" went to
 * the model and got "I don't have access to the current status of your timers". Now it is a routine phrase answered from the
 * timers Robot set (TimerAsk), without the model; none, or all run out, said plainly; a clock app's own timers never guessed.
 */
class TimerAskTest {
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 11, 4, 15, 0, 0, 0, ZoneOffset.UTC)
    private fun route(s: String) = RoutineRouter.route(s, now)
    private val zone: ZoneId = ZoneOffset.UTC
    private val t0 = now.toInstant().toEpochMilli()
    private val en = Langs.of(Lang.EN)
    private val fr = Langs.of(Lang.FR)

    @Test fun timeLeftQuestionsAreTimerLeftInEnglish() {
        listOf("how much time is left on my timer", "How much time is left on my timer?", "how long is left on the timer",
            "what's left on my timer", "time left on my timer", "how many minutes are left on the timer", "how much longer on my timer",
            "when does my timer go off", "how long until my timer goes off", "is my timer still running", "check my timer",
            "hey robot, how much time is left on my timer").forEach {
            assertEquals(it, RoutineCommand.TimerLeft(french = false), route(it))
        }
    }

    @Test fun timeLeftQuestionsAreTimerLeftInFrench() {
        listOf("il reste combien de temps à ma minuterie", "Il reste combien de temps à ma minuterie ?",
            "combien de temps reste-t-il sur ma minuterie", "combien de temps il reste au minuteur", "quand ma minuterie va sonner",
            "il reste combien de temps au compte à rebours").forEach {
            assertEquals(it, RoutineCommand.TimerLeft(french = true), route(it))
        }
    }

    @Test fun otherTimerWordsAreNotTimeLeft() {
        listOf("set a timer for 5 minutes", "how much time is left", "how long should I boil an egg", "what is a timer",
            "how much time is left on my alarm", "how much time is left on the timer app", "add 2 minutes to the timer",
            "mets une minuterie de 5 minutes", "combien de temps reste-t-il").forEach {
            assertTrue(it, route(it) !is RoutineCommand.TimerLeft)
        }
        // "cancel the timer" stays the cancel.
        assertEquals(RoutineCommand.CantCancel(timer = true, french = false), route("cancel the timer"))
    }

    @Test fun aRunningTimerSaysWhatIsLeftAndWhenItEnds() {
        val ts = TimerAsk.added(emptyList(), t0, 60)
        val a = TimerAsk.answer(ts, t0 + 28_000, zone)
        assertEquals("Your timer for 1 minute has 32 seconds left. It ends at 15:01.", a.render(en).replace("3:01 PM", "15:01"))
        assertTrue(a.render(fr), a.render(fr).startsWith("Il reste 32 secondes à ta minuterie de 1 minute."))
    }

    @Test fun noneSetOrAllRunOutIsSaidPlainly() {
        assertEquals("Robot hasn't set a timer. It can't see one set in your clock app: check there.", TimerAsk.answer(emptyList(), t0, zone).render(en))
        val ts = TimerAsk.added(emptyList(), t0, 60)
        val ran = TimerAsk.answer(ts, t0 + 120_000, zone).render(en)
        assertTrue(ran, ran.startsWith("Your timer for 1 minute ran out at "))
        assertTrue(ran, ran.endsWith("Robot can't see one set in your clock app: check there."))
        // Long gone: forgotten, so none set.
        assertEquals(TimerAsk.answer(emptyList(), t0, zone), TimerAsk.answer(ts, t0 + TimerAsk.KEEP_MS + 60_000, zone))
    }

    @Test fun twoRunningTheFirstToEndFirstAndTheRecordReadsBack() {
        val ts = TimerAsk.added(TimerAsk.added(emptyList(), t0, 600), t0 + 1_000, 120)
        val a = TimerAsk.answer(ts, t0 + 61_000, zone).render(en)
        assertTrue(a, a.startsWith("Your timer for 2 minutes has 1 minute left."))
        assertTrue(a, a.contains("Your timer for 10 minutes has 8 minutes and 59 seconds left."))
        assertEquals(ts, TimerAsk.decode(TimerAsk.encode(ts)))
        assertEquals(emptyList<RobotTimer>(), TimerAsk.decode("not json"))
        assertNull(TimerAsk.decode(null).firstOrNull())
    }
}
