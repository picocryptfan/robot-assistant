// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityRecord
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.lock.LockExtras
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockGate
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.assistant.RobotSession
import app.heyrobot.assistant.ask.AskRobotActivity
import app.heyrobot.ui.files.FilesShareActivity
import app.heyrobot.ui.lists.ListsWidgetSetup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * LS13 (LT8 step 4 on the Pixel 8): during a lock for someone else, the widget's list picker, opened in its own task, stayed
 * in front for 10 s with no cover, though LockGate.ROBOT_COVERED lists it. Not its task: its window filtered obscured
 * touches, which since Android 14 makes a view accessibility data-sensitive, and Android sends such a window's events only
 * to accessibility tools. Phone lock's service isn't one, on purpose, so it never heard the picker come up. Now the two
 * windows that filter touches (the picker and Robot Files' share target) keep the filter and say their window isn't
 * sensitive (what's in it still is), and every covered window tells :lock it is in front, as MainActivity does, so the
 * cover never rests on Android's event alone, whatever task or process the window runs in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoveredWindowsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val app get() = ctx as Application
    private var service: ServiceController<LockAccessibilityService>? = null
    private val windows = ArrayList<ActivityController<out Activity>>()
    /** :lock's receiver, for what Robot's windows send it (Robolectric delivers an explicit broadcast only to a registered receiver). */
    private val receiver = LockAlarmReceiver()
    private var listening = false

    @After fun tearDown() {
        for (w in windows) runCatching { w.pause().stop().destroy() }
        if (listening) ctx.unregisterReceiver(receiver)
        service?.destroy()
        File(LockStore.dir(ctx), LockStore.STATE).delete()
        File(LockStore.dir(ctx), LockStore.LOCKS).delete()
        LockEngine.refresh(ctx)
        settle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A few milliseconds on: Robolectric's clock stands still otherwise, and a tie with the window kept goes to it. */
    private fun later() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))

    /** Broadcasts delivered, the watcher's and the receiver's threads done, and what they posted to the service run. */
    private fun settle() {
        repeat(3) {
            idle()
            val until = System.currentTimeMillis() + 5_000
            while (Thread.getAllStackTraces().keys.any { (it.name == "robot-lock" || it.name == "robot-lock-front") && it.isAlive } &&
                System.currentTimeMillis() < until) Thread.sleep(20)
        }
        idle()
    }

    /** Phone lock on, for someone else, with a Time of day lock open now; its service connected as Android connects it. */
    private fun aLockForSomeoneElse() {
        ctx.registerReceiver(receiver, IntentFilter().apply { addAction(LockSignals.ACTION_FRONT); addAction(LockSignals.ACTION_EDIT_END) }, Context.RECEIVER_NOT_EXPORTED)
        listening = true
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        val homework = PhoneLock("lk_hw00", "Homework", LockRule.Time(TimeWindow(now.minusHours(1), now.plusHours(1)), DayOfWeek.entries.toSet()),
            extras = LockExtras(apps = listOf("org.example.reader")))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true, forWho = LockFor.SOMEONE_ELSE), listOf(homework)) })
        service = Robolectric.buildService(LockAccessibilityService::class.java).create().also { s ->
            LockAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(s.get())
        }
        settle()
        assertTrue(LockEngine.snapshot!!.active)
        // The cover is up already (the lock started with the screen on); the window the service keeps is the cover.
        service!!.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = ctx.packageName; className = LockSignals.COVER; eventTime = SystemClock.uptimeMillis()
        })
        idle()
        covered()
        shadowOf(app).clearBroadcastIntents()
        later()
    }

    /** The cover was started since the last look. */
    private fun covered(): Boolean {
        var cover = false
        while (true) {
            val i = shadowOf(app).nextStartedActivity ?: break
            if (i.component?.className == LockSignals.COVER) cover = true
        }
        return cover
    }

    /** What Robot's windows told :lock: each "in front" with the window it named (whatever the extra is called). */
    private fun fronts(): List<String?> = shadowOf(app).broadcastIntents
        .filter { it.action == LockSignals.ACTION_FRONT && it.component?.className == LockSignals.RECEIVER }
        .map { i -> i.extras?.let { e -> e.keySet().firstNotNullOfOrNull { k -> e.getString(k) } } }

    private fun picker(): ActivityController<ListsWidgetSetup> =
        Robolectric.buildActivity(ListsWidgetSetup::class.java, Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 42))
            .also { windows += it }

    private fun share(): ActivityController<FilesShareActivity> =
        Robolectric.buildActivity(FilesShareActivity::class.java, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hi"))
            .also { windows += it }

    private fun door(): ActivityController<AskRobotActivity> =
        Robolectric.buildActivity(AskRobotActivity::class.java, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hi"))
            .also { windows += it }

    /** The window-state event Android builds for [w] as it comes up (ViewRootImpl sends it from the decor view). */
    private fun windowEvent(w: Activity): AccessibilityEvent =
        AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).also { w.window.decorView.onInitializeAccessibilityEvent(it) }

    /** The event's own flag (package-private; the system's dispatch reads it to keep the event from services that aren't tools). */
    private fun sensitive(e: AccessibilityEvent): Boolean =
        AccessibilityRecord::class.java.getDeclaredMethod("isAccessibilityDataSensitive").apply { isAccessible = true }.invoke(e) as Boolean

    /**
     * The picker and the share target still refuse a tap while another window covers theirs, but Android may tell Phone lock
     * they came up: their window, and so its window-state event, isn't data-sensitive. What they show still is.
     */
    @Test fun windowsThatFilterTouchesStillReachPhoneLock() {
        for (c in listOf(picker(), share(), door())) {
            val w = c.setup().get()
            val decor = w.window.decorView
            val name = w.javaClass.simpleName
            assertTrue(name, decor.filterTouchesWhenObscured)
            assertFalse("$name's window is hidden from Phone lock", decor.isAccessibilityDataSensitive)
            assertFalse("$name's window event is hidden from Phone lock", sensitive(windowEvent(w)))
            assertTrue("$name's content isn't marked", decor.findViewById<View>(android.R.id.content).isAccessibilityDataSensitive)
        }
    }

    /**
     * fix-panel-filter: the panel's own window (the voice session's dialog, which no activity's filter reaches) refuses a tap
     * another window covers, as its popups do, and Android still tells Phone lock it came up. The system makes the window
     * (ensureWindowCreated, package-private) before it asks for the content view, so the test does the same.
     */
    @Test fun thePanelsWindowRefusesCoveredTapsAndStillReachesPhoneLock() {
        val session = RobotSession(ApplicationProvider.getApplicationContext<Context>())
        android.service.voice.VoiceInteractionSession::class.java.getDeclaredMethod("ensureWindowCreated").apply { isAccessible = true }.invoke(session)
        session.onCreateContentView()
        val decor = session.window.window!!.decorView
        assertTrue("the panel's window lets a covered tap through", decor.filterTouchesWhenObscured)
        assertFalse("the panel's window is hidden from Phone lock", decor.isAccessibilityDataSensitive)
        val e = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).also { decor.onInitializeAccessibilityEvent(it) }
        assertFalse("the panel's window event is hidden from Phone lock", sensitive(e))
        assertTrue("the panel's content isn't marked", decor.findViewById<View>(android.R.id.content).isAccessibilityDataSensitive)
    }

    /**
     * LT8 step 4: the picker in its own task, opened during a lock with no window event reaching :lock, tells :lock it is in
     * front, and the cover comes.
     */
    @Test fun thePickerInItsOwnTaskIsCovered() {
        aLockForSomeoneElse()
        picker().setup()
        settle()
        assertTrue(fronts().toString(), fronts().contains(ListsWidgetSetup::class.java.name))
        assertTrue(covered())
    }

    /** The share target too: it refuses during a lock itself, and is covered as well. */
    @Test fun theShareTargetIsCovered() {
        aLockForSomeoneElse()
        share().setup()
        settle()
        assertTrue(fronts().toString(), fronts().contains(FilesShareActivity::class.java.name))
        assertTrue(covered())
    }

    /**
     * The check that catches the next window: every window the gate covers tells :lock itself as it comes to the front, but
     * Robot's app, which PhoneLockPages tells (with the edit session's timing); no window the cover leaves open does.
     */
    @Test fun everyCoveredWindowSaysItIsInFront() {
        assertEquals(LockSignals.MAIN, LockGate.ROBOT_APP)
        for (c in LockGate.ROBOT_COVERED) assertEquals(c, c != LockSignals.MAIN, LockFrontWatch.tells(c))
        for (c in LockGate.ROBOT_EXEMPT.keys) assertFalse(c, LockFrontWatch.tells(c))
    }

    /** Fail closed: a word that names a window the cover leaves open, or none, counts as Robot's app, and is covered. */
    @Test fun aWordFromRobotOnlyCovers() {
        aLockForSomeoneElse()
        LockSignals.front(ctx, SystemClock.uptimeMillis(), LockSignals.CODE)
        settle()
        assertTrue(covered())
        later()
        LockSignals.front(ctx, SystemClock.uptimeMillis(), "org.example.game.Main")
        settle()
        assertTrue(covered())
    }

    /**
     * LS13 low 1: locks.json damaged after :lock read it (or unreadable in this process). :lock still holds the lock, so the
     * window tells it it is in front, as §5.2 says, rather than resting on Android's event alone.
     */
    @Test fun anUnreadableLocksFileStillSendsTheWord() {
        aLockForSomeoneElse()
        File(LockStore.dir(ctx), LockStore.LOCKS).writeText("{not json")
        picker().setup()
        settle()
        assertTrue(fronts().toString(), fronts().contains(ListsWidgetSetup::class.java.name))
    }

    /** With no lock on, a covered window opens as before and tells :lock nothing (Phone lock is off). */
    @Test fun noLockNoWord() {
        picker().setup()
        settle()
        assertTrue(fronts().isEmpty())
        assertFalse(covered())
    }
}
