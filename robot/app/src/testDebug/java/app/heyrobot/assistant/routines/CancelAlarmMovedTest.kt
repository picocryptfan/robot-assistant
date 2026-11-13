// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.EventText
import app.heyrobot.core.Executed
import app.heyrobot.core.RoutineCommand
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ToolStrings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.ZoneId

/**
 * fix-batch-15 L1 and fix-batch-15-lows d (review-batch-15): "cancel my alarm" asks about the next alarm; when it moved while
 * the card waited (it rang, or another was set), that card is declined (nothing was sent) and Robot asks about the one now
 * next. The next alarm is Robolectric's AlarmManager (setAlarmClock / getNextAlarmClock), the clock a fake host's launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CancelAlarmMovedTest {
    private class Fake(val answers: List<Boolean>, val onAsk: (Int) -> Unit = {}, val onDismiss: () -> Unit = {}) : PanelRoutines.Host {
        val asks = ArrayList<Card>()
        val shown = ArrayList<Card>()
        val launched = ArrayList<Intent>()
        override val speak = false
        override val locked = false
        override fun text(replyId: String, text: String) = Unit
        override fun append(replyId: String, text: String) = Unit
        override fun say(sentence: String, personal: Boolean) = Unit
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?): Boolean {
            val n = asks.size
            asks += card
            onAsk(n)
            return answers.getOrElse(n) { false } // a third ask is a no: a broken check can't loop forever
        }
        override fun show(replyId: String, card: Card) { shown += card }
        override suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed? = null
        override fun finish(replyId: String) = Unit
        override fun quoted(replyId: String) = Unit
        override suspend fun leave() = Unit
        override fun launch(intent: Intent): Boolean {
            launched += intent
            if (intent.action == AlarmClock.ACTION_DISMISS_ALARM) onDismiss()
            return true
        }
        override fun langs(said: String?, router: Lang?): Langs = Langs.of(Lang.EN)
        fun dismissed() = launched.filter { it.action == AlarmClock.ACTION_DISMISS_ALARM }
            .map { it.getIntExtra(AlarmClock.EXTRA_HOUR, -1) to it.getIntExtra(AlarmClock.EXTRA_MINUTES, -1) }
        fun last(card: Card) = shown.last { it.id == card.id }.status
    }

    private val alarms get() = VoiceHub.app.getSystemService(AlarmManager::class.java)
    private fun pi(n: Int) = PendingIntent.getBroadcast(VoiceHub.app, n, Intent("test.alarm.$n"), PendingIntent.FLAG_IMMUTABLE)
    private fun set(n: Int, at: Long) = alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pi(n))
    private fun drop(n: Int) = alarms.cancel(pi(n))
    private fun hm(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).let { it.hour to it.minute }
    private fun title(at: Long) = hm(at).let { (h, m) -> ToolStrings.alarmTurnOffTitle("time" to EventText.clock(h, m)).render(Langs.of(Lang.EN)) }

    // Two alarms a minute apart from a whole minute: A is next, B after it.
    private val a = (System.currentTimeMillis() / 60_000 + 180) * 60_000
    private val b = a + 3_600_000

    @Before fun hub() {
        VoiceHub.init(ApplicationProvider.getApplicationContext())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        set(1, a); set(2, b)
        assertEquals(a, alarms.nextAlarmClock?.triggerTime)
    }

    private fun cancel(host: Fake): Fake {
        runBlocking { PanelRoutines.handle("reply", RoutineCommand.CantCancel(timer = false, french = false), host, "cancel my alarm") }
        return host
    }

    /** L1: A went while its card waited; the yes sends nothing for A and the next card asks about B. */
    @Test fun movedWhileTheCardWaitedItAsksAboutTheOneNowNext() {
        val h = cancel(Fake(listOf(true, false), onAsk = { if (it == 0) drop(1) }))
        assertEquals(listOf(title(a), title(b)), h.asks.map { it.summary })
        assertEquals(emptyList<Pair<Int, Int>>(), h.dismissed())
        assertEquals(ActionStatus.DECLINED, h.last(h.asks[1]))
    }

    /** d: the first card is declined, not failed (nothing went to the clock); a yes on the second turns off B, not A. */
    @Test fun theMovedCardIsDeclinedAndTheNextOneIsTurnedOff() {
        val h = cancel(Fake(listOf(true, true), onAsk = { if (it == 0) drop(1) }, onDismiss = { drop(2) }))
        assertEquals(listOf(ActionStatus.DECLINED), h.shown.filter { it.id == h.asks[0].id }.map { it.status })
        assertEquals(listOf(hm(b)), h.dismissed())
        assertEquals(ActionStatus.DONE, h.last(h.asks[1]))
    }

    /** The check's other side: nothing moved, so one card, A turned off and the card done. */
    @Test fun unmovedItAsksOnceAndTurnsOffTheOneAsked() {
        val h = cancel(Fake(listOf(true), onDismiss = { drop(1) }))
        assertEquals(listOf(title(a)), h.asks.map { it.summary })
        assertEquals(listOf(hm(a)), h.dismissed())
        assertEquals(ActionStatus.DONE, h.last(h.asks[0]))
        assertTrue(h.shown.none { it.status == ActionStatus.DECLINED })
    }
}
