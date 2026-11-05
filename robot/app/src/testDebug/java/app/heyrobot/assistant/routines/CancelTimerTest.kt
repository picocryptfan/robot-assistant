// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.Card
import app.heyrobot.core.Executed
import app.heyrobot.core.RoutineCommand
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.TimerAsk
import app.heyrobot.sys.RobotTimers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * fix-timers-1.2.1 (everyday-1.2.1, finding 1): "cancel the timer" got "Robot can't cancel or stop timers" and a confirm card
 * to open the clock app. Now Robot sends AlarmClock's ACTION_DISMISS_TIMER (stops a ringing timer) and, unlocked, opens the
 * clock's timers at once with no card (Android has nothing public to cancel a running one); locked, it says how.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CancelTimerTest {
    private class Fake(override val locked: Boolean = false, val opens: Boolean = true, val lang: Lang = Lang.EN,
                       override val handsFree: Boolean = false) : PanelRoutines.Host {
        val said = ArrayList<String>()
        val launched = ArrayList<String>()
        var text = ""
        var asked = 0
        var left = false
        override val speak = true
        override fun text(replyId: String, text: String) { this.text = text }
        override fun append(replyId: String, text: String) = Unit
        override fun say(sentence: String, personal: Boolean) { said += sentence }
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?): Boolean { asked++; return false }
        override fun show(replyId: String, card: Card) = Unit
        override suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed? = null
        override fun finish(replyId: String) = Unit
        override fun quoted(replyId: String) = Unit
        override suspend fun leave() { left = true }
        override fun launch(intent: Intent): Boolean { launched += intent.action.orEmpty(); return opens }
        override fun langs(said: String?, router: Lang?): Langs = Langs.of(lang)
    }

    @Before fun hub() { VoiceHub.init(ApplicationProvider.getApplicationContext()); RobotTimers.clear(VoiceHub.app) }

    private fun cancel(host: Fake, french: Boolean = false, words: String = "cancel the timer"): Fake {
        runBlocking { PanelRoutines.handle("reply", RoutineCommand.CantCancel(timer = true, french = french), host, words) }
        return host
    }

    @Test fun unlockedItStopsARingingTimerAndOpensTheTimersWithNoCard() {
        val h = cancel(Fake())
        assertEquals(0, h.asked)
        assertEquals(listOf(AlarmClock.ACTION_DISMISS_TIMER, AlarmClock.ACTION_SHOW_TIMERS), h.launched)
        assertTrue(h.left)
        assertEquals("Robot asked your clock app to stop any timer that's ringing. Android doesn't let Robot cancel a running timer: " +
            "your clock's timers are open, cancel it there.", h.text)
        assertEquals(2, h.said.size)
    }

    @Test fun inFrench() {
        val h = cancel(Fake(lang = Lang.FR), french = true, words = "arrête la minuterie")
        assertEquals(0, h.asked)
        assertTrue(h.text, h.text.startsWith("Robot a demandé à ton appli d’horloge d’arrêter toute minuterie qui sonne."))
        assertTrue(h.text, h.text.endsWith("les minuteries de ton horloge sont ouvertes, annule-la là."))
    }

    @Test fun lockedItOnlyStopsARingingOneAndSaysHow() {
        val h = cancel(Fake(locked = true))
        assertEquals(listOf(AlarmClock.ACTION_DISMISS_TIMER), h.launched)
        assertFalse(h.left)
        assertTrue(h.text, h.text.endsWith("To cancel a running timer, unlock and open your clock app."))
    }

    /** fix-1.2.1-b: in car mode or a wake-word session no clock page comes up in front of you; one short spoken line. */
    @Test fun handsFreeItOpensNoClockAndSaysOneShortLine() {
        val h = cancel(Fake(handsFree = true))
        assertEquals(listOf(AlarmClock.ACTION_DISMISS_TIMER), h.launched)
        assertFalse(h.left)
        assertEquals(0, h.asked)
        assertEquals("Robot asked your clock to stop a ringing timer. Cancel a running one in your clock app.", h.text)
        assertEquals(listOf(h.text), h.said)
    }

    @Test fun handsFreeInFrenchAndLocked() {
        val h = cancel(Fake(locked = true, lang = Lang.FR, handsFree = true), french = true, words = "arrête la minuterie")
        assertEquals(listOf(AlarmClock.ACTION_DISMISS_TIMER), h.launched)
        assertFalse(h.left)
        assertEquals("Robot a demandé à ton horloge d’arrêter une minuterie qui sonne. Annule une minuterie en cours dans ton appli d’horloge.", h.text)
        assertEquals(1, h.said.size)
    }

    /**
     * fix-everyday-e (everyday-1.2.2-a: "cancel the timer" showed nothing): a timer Robot set that still runs is named with its
     * time left; no dismiss goes first (nothing of Robot's rings), so the clock's timers are the one page that opens; and
     * "how much time is left" after it no longer says for sure it runs.
     */
    @Test fun aRunningTimerRobotSetIsNamedAndOnlyTheClocksTimersOpen() {
        val now = System.currentTimeMillis()
        RobotTimers.set(VoiceHub.app, 300, now - 60_000)
        val h = cancel(Fake())
        assertEquals(listOf(AlarmClock.ACTION_SHOW_TIMERS), h.launched)
        assertTrue(h.left)
        assertTrue(h.text, h.text.matches(Regex("""Your timer for 5 minutes has (4 minutes|3 minutes and 59 seconds) left\. It ends at .+\. """ +
            """Android doesn't let Robot cancel a running timer: your clock's timers are open, cancel it there\.""")))
        assertTrue(RobotTimers.load(VoiceHub.app).single().cancelAsked)
        val left = TimerAsk.answer(RobotTimers.load(VoiceHub.app), now, java.time.ZoneId.systemDefault()).render(Langs.of(Lang.EN))
        assertTrue(left, left.contains(", unless you cancelled it in your clock app."))
    }

    @Test fun oneThatRanOutMayRingSoTheDismissStillGoes() {
        val now = System.currentTimeMillis()
        RobotTimers.set(VoiceHub.app, 60, now - 120_000)
        RobotTimers.set(VoiceHub.app, 300, now - 10_000)
        val h = cancel(Fake())
        assertEquals(listOf(AlarmClock.ACTION_DISMISS_TIMER, AlarmClock.ACTION_SHOW_TIMERS), h.launched)
        assertTrue(h.text, h.text.contains("left. It ends at "))
        assertTrue(h.text, h.text.contains("Robot asked your clock app to stop any timer that's ringing."))
    }

    @Test fun handsFreeOrLockedARunningOneIsNamedWithNoPage() {
        RobotTimers.set(VoiceHub.app, 300, System.currentTimeMillis())
        val hf = cancel(Fake(handsFree = true))
        assertEquals(emptyList<String>(), hf.launched)
        assertTrue(hf.text, hf.text.endsWith("Android doesn't let Robot cancel a running timer: do it in your clock app."))
        assertEquals(2, hf.said.size)
        val locked = cancel(Fake(locked = true))
        assertEquals(emptyList<String>(), locked.launched)
        assertTrue(locked.text, locked.text.endsWith("To cancel a running timer, unlock and open your clock app."))
    }

    @Test fun noClockAppSaysSo() {
        val h = cancel(Fake(opens = false))
        assertEquals(0, h.asked)
        assertEquals("No clock app on this phone takes timers.", h.text)
    }
}
