// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.people

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import app.heyrobot.core.i18n.ReadyStrings
import app.heyrobot.sys.AppLanguage
import java.time.Duration
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.messages.ActionRef
import app.heyrobot.core.messages.IncomingMessage
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.ReadyAnswer
import app.heyrobot.core.messages.ReadyAnswerSettings
import app.heyrobot.core.messages.ReadySource
import app.heyrobot.core.messages.ReplyOutcome
import app.heyrobot.lock.LockEngine
import app.heyrobot.lock.LockStore
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.LockShade
import app.heyrobot.sys.ReadyAnswerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** review-ready-answers-b: the draft's notice and its Send, with Android's own notification classes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadyAnswerTapRunTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)
    private val now = System.currentTimeMillis()
    private val app = MessageApp.values().first()
    private val thread = MessageThread(app, "c1", "k1", "Sam", false,
        listOf(IncomingMessage("m1", app, "c1", "Sam", "+15555550123", "What time do you land?", now - 1_000, false, true)),
        ActionRef(0, "text_reply", true, false, false), null, null, now)
    private val answer = ReadyAnswer(ReadySource.CALENDAR, "From my calendar: Flight AC 123, lands at 15:00.", "Read from your calendar at 13:02.", Lang.EN)

    @Before fun on() { ReadyAnswerStore.save(ctx, ReadyAnswerSettings(on = true)) }

    @After fun lockOff() {
        File(LockStore.dir(ctx), LockStore.STATE).delete()
        File(LockStore.dir(ctx), LockStore.LOCKS).delete()
        LockEngine.refresh(ctx)
    }

    private fun lockOn(who: LockFor) {
        val t = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        val l = PhoneLock("lk_ready0", "Homework", LockRule.Time(TimeWindow(t.minusHours(1), t.plusHours(1)), DayOfWeek.entries.toSet()))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true, forWho = who), listOf(l)) })
        LockEngine.refresh(ctx)
        assertTrue(PhoneLockNow.on(ctx))
    }

    private fun drafts(): List<StatusBarNotification> =
        nm.activeNotifications.filter { it.id - LockShade.READY_ANSWER_BASE in 0 until LockShade.READY_ANSWER_SLOTS }
    private fun posted(at: Long = now): Notification {
        ReadyAnswerTap.post(ctx, thread, "Sam", "What time do you land?", answer, at)
        return drafts().single().notification
    }
    private fun send(n: Notification): Intent = shadowOf(n.actions.first { it.title == "Send" }.actionIntent).savedIntent
    /** The one notice left, its text and long text. */
    private fun shown(): String = drafts().single().notification.extras.let { e ->
        listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT).joinToString("\n") { e.getCharSequence(it)?.toString().orEmpty() }
    }

    /** Low 8: what Send holds is the conversation's key and the draft, not the thread, and it goes at its time without another post. */
    @Test fun aHeldDraftGoesAtItsTime() {
        ReadyAnswerTap.clear(ctx)
        assertTrue(Class.forName("app.heyrobot.people.ReadyAnswerTap\$Held").declaredFields.none { it.type == MessageThread::class.java })
        posted()
        assertEquals(1, ReadyAnswerTap.heldCount())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ReadyAnswerTap.TTL + 1_000))
        assertEquals(0, ReadyAnswerTap.heldCount())
    }

    /** Low 9: the notice's buttons, title and channel are in the app's language; only the draft is in the question's. */
    @Test fun theNoticeIsInTheAppsLanguage() {
        val ui = AppLanguage.langs(ctx)
        val other = Langs.of(if (ui.ui == Lang.FR) Lang.EN else Lang.FR)
        ReadyAnswerTap.post(ctx, thread, "Sam", "?", answer.copy(lang = other.ui), now)
        val n = drafts().single().notification
        assertTrue(ReadyStrings.send().render(ui) != ReadyStrings.send().render(other))
        assertTrue(n.actions.any { it.title == ReadyStrings.send().render(ui) })
        assertEquals(ReadyStrings.channel().render(ui), nm.getNotificationChannel("ready_answers").name.toString())
    }

    /** Low 11: a messaging notice never reads ready-answers.json on the thread it came on (the main one). */
    @Test fun theSwitchIsReadOffTheMainThread() {
        val q = mutableListOf<Runnable>()
        val was = ReadyAnswerTap.run
        try {
            ReadyAnswerTap.run = { q += it }
            ReadyAnswerStore.save(ctx, ReadyAnswerSettings(on = false))
            ReadyAnswerTap.posted(ctx, thread.copy(conversationId = "c-off"))
            assertEquals(1, q.size)
        } finally { ReadyAnswerTap.run = was }
    }

    /** Low 12: the draft and its Send never reach a paired watch. */
    @Test fun theDraftStaysOnThePhone() {
        val n = posted()
        assertTrue((n.flags and Notification.FLAG_LOCAL_ONLY) != 0)
        assertTrue((n.publicVersion.flags and Notification.FLAG_LOCAL_ONLY) != 0)
    }

    /** Low D: a lock for someone else clears the drafts and what Send holds: nothing shows during it, nothing is sent after. */
    @Test fun aLockForSomeoneElseDropsTheDrafts() {
        val i = send(posted())
        lockOn(LockFor.SOMEONE_ELSE)
        assertEquals(0, drafts().size)
        ReadyAnswerTap.post(ctx, thread, "Sam", "What time do you land?", answer, now)
        assertEquals(0, drafts().size)
        lockOff()
        ReadyAnswerTap.act(ctx, i)
        assertTrue(shown(), "Not sent: that answer has expired." in shown())
    }

    /** Low D: Send tapped during a lock for someone else shows nothing at all. */
    @Test fun sendDuringALockForSomeoneElseShowsNothing() {
        val i = send(posted())
        lockOn(LockFor.SOMEONE_ELSE)
        ReadyAnswerTap.act(ctx, i)
        assertEquals(0, drafts().size)
    }

    /** Low C: Send with ready answers off, during your own lock or past its time says why, never just goes. */
    @Test fun aSendThatCantGoSaysWhy() {
        var i = send(posted())
        ReadyAnswerStore.save(ctx, ReadyAnswerSettings(on = false))
        ReadyAnswerTap.act(ctx, i)
        assertTrue(shown(), "Not sent: ready answers are off." in shown())
        nm.cancelAll()
        ReadyAnswerStore.save(ctx, ReadyAnswerSettings(on = true))
        i = send(posted())
        lockOn(LockFor.ME)
        ReadyAnswerTap.act(ctx, i)
        assertTrue(shown(), "Not sent: your phone was locked." in shown())
        lockOff()
        nm.cancelAll()
        i = send(posted(now - ReadyAnswerTap.TTL - 1_000))
        ReadyAnswerTap.act(ctx, i)
        assertTrue(shown(), "Not sent: that answer has expired." in shown())
    }

    /** Low C: the process starting again leaves no Send that does nothing; one tapped from before says why. */
    @Test fun afterARestartNoDeadSend() {
        val i = send(posted())
        ReadyAnswerTap.start(ctx)
        assertEquals(0, drafts().size)
        ReadyAnswerTap.act(ctx, i)
        assertTrue(shown(), "Not sent: that answer has expired." in shown())
    }

    /** Low C: each way the app's reply can fail is named as it is. */
    @Test fun everyFailedSendSaysItsReason() {
        val en = Langs.of(Lang.EN)
        fun why(o: ReplyOutcome) = ReadyAnswerTap.whyNot(null, o, "Sam", "Signal", en)
        assertTrue(why(ReplyOutcome.FAILED), "Signal didn't take the answer" in why(ReplyOutcome.FAILED))
        assertTrue(why(ReplyOutcome.CHANGED), "conversation changed" in why(ReplyOutcome.CHANGED))
        assertTrue(why(ReplyOutcome.NEW_MESSAGES), "Sam wrote again" in why(ReplyOutcome.NEW_MESSAGES))
        assertTrue(why(ReplyOutcome.LOCKED), "your phone was locked" in why(ReplyOutcome.LOCKED))
        assertTrue(why(ReplyOutcome.OPEN_CHAT), "settings" in why(ReplyOutcome.OPEN_CHAT))
        assertTrue(why(ReplyOutcome.CANCELED_PI), "gone from Signal" in why(ReplyOutcome.CANCELED_PI))
    }
}
