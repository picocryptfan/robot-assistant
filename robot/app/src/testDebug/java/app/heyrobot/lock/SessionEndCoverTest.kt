// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.lock.LockExtras
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.ui.settings.PhoneLockPages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Phone lock during a lock for someone else: the cover's Change locks › code opens Settings › Phone lock, and when the
 * edit session ends with Robot's app still in front (Back out of the pages, or its 5 minutes run out), the cover comes
 * back. On a Pixel 8 Android sent no window event for Robot's app coming back from the code pad, so the window the
 * service kept was the pad (allowed) and Robot's settings stayed open. Here too no event comes for it: Robot's app says it
 * is in front (PhoneLockPages, from MainActivity's onResume and onPause), :lock's receiver passes that on, the gate decides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionEndCoverTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val app get() = ctx as Application
    private lateinit var service: ServiceController<LockAccessibilityService>
    private lateinit var svc: LockAccessibilityService
    /** Stands in for MainActivity: PhoneLockPages hears its onResume and onPause. */
    private lateinit var robot: Activity
    private val reader = "org.example.reader"
    /** :lock's receiver, for what Robot's app sends it (Robolectric delivers an explicit broadcast only to a registered receiver). */
    private val receiver = LockAlarmReceiver()

    private fun listen() =
        ctx.registerReceiver(receiver, IntentFilter().apply { addAction(LockSignals.ACTION_FRONT); addAction(LockSignals.ACTION_EDIT_END) }, Context.RECEIVER_NOT_EXPORTED)

    @Before fun setUp() {
        listen()
        // Phone lock on, for someone else, with a Time of day lock open now that lets the reader through.
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        val homework = PhoneLock("lk_hw00", "Homework", LockRule.Time(TimeWindow(now.minusHours(1), now.plusHours(1)), DayOfWeek.entries.toSet()),
            extras = LockExtras(apps = listOf(reader)))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true, forWho = LockFor.SOMEONE_ELSE), listOf(homework)) })
        // The service as Android connects it: every engine change reaches it.
        service = Robolectric.buildService(LockAccessibilityService::class.java).create()
        svc = service.get()
        LockAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(svc)
        settle()
        assertTrue(LockEngine.snapshot!!.active)
        robot = Robolectric.buildActivity(Activity::class.java).setup().get()
        // The cover is up; Change locks opens the code pad over it, in the cover's task.
        event(ctx.packageName, LockSignals.COVER)
        event(ctx.packageName, LockSignals.CODE)
        covered()
        shadowOf(app).clearBroadcastIntents()
    }

    @After fun tearDown() {
        PhoneLockPages.paused()
        ctx.unregisterReceiver(receiver)
        service.destroy()
        File(LockStore.dir(ctx), LockStore.STATE).delete()
        File(LockStore.dir(ctx), LockStore.LOCKS).delete()
        LockEngine.refresh(ctx)
        settle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A few milliseconds on: Robolectric's clock stands still otherwise, and a tie with the window kept goes to it. */
    private fun later() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))

    /** Broadcasts delivered, the receivers' and PhoneLockPages' threads done, and what they posted to the service run. */
    private fun settle() {
        repeat(3) {
            idle()
            val until = System.currentTimeMillis() + 5_000
            while (Thread.getAllStackTraces().keys.any { (it.name == "robot-lock" || it.name == "robot-lock-front") && it.isAlive } &&
                System.currentTimeMillis() < until) Thread.sleep(20)
        }
        idle()
    }

    private fun event(pkg: String, cls: String, at: Long = SystemClock.uptimeMillis()) {
        svc.onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = pkg; className = cls; eventTime = at })
        idle()
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

    private fun sent(action: String): Boolean = shadowOf(app).broadcastIntents.any { it.action == action && it.component?.className == LockSignals.RECEIVER }

    private fun editEnds() = shadowOf(app).broadcastIntents.filter { it.action == LockSignals.ACTION_EDIT_END && it.component?.className == LockSignals.RECEIVER }

    /** The window the service keeps as the one in front. */
    private fun kept(): String? = field("frontPkg")
    private fun keptClass(): String? = field("frontCls")
    private fun field(name: String) = LockAccessibilityService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(svc) as String?

    private fun stateCantBeRead() = File(LockStore.dir(ctx), LockStore.STATE).writeText("{")

    /** The session's 5 minutes run out as :lock sees them (its alarm's refresh), without waiting for them. */
    private fun sessionRunsOut() {
        LockStore.editState(ctx) { it.copy(edit = System.currentTimeMillis() - 1) }
        LockEngine.refresh(ctx)
        settle()
    }

    /** The right code on the pad: a 5-minute edit session ([endsIn] to make it shorter here). */
    private fun code(endsIn: Long? = null) {
        LockEngine.openEdit(ctx)
        if (endsIn != null) LockStore.editState(ctx) { it.copy(edit = System.currentTimeMillis() + endsIn) }
        LockEngine.refresh(ctx)
        settle()
    }

    /** The pad opens Settings › Phone lock and closes: Robot's app is back in front, and no window event says so. */
    private fun backInRobot() {
        later()
        PhoneLockPages.resumed(robot)
        settle()
    }

    /** Settings › Phone lock shows, then Back to Robot's other settings: a second on, the pages end the session. */
    private fun backOutOfThePages() {
        PhoneLockPages.opened()
        PhoneLockPages.closed(ctx)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        settle()
    }

    @Test fun backOutOfPhoneLockBringsTheCoverBack() {
        code()
        // Robot's app comes back from the pad, and its word as it does never reaches :lock: the window kept is the pad's.
        ctx.unregisterReceiver(receiver)
        backInRobot()
        listen()
        assertEquals(LockSignals.CODE, keptClass())
        assertFalse(covered())
        // Back to Robot's other settings: the session's end says since when Robot's app is in front, and the cover comes back.
        backOutOfThePages()
        assertTrue(editEnds().single().hasExtra(LockSignals.EXTRA_AT))
        assertEquals(0L, LockStore.readState(ctx).edit)
        assertTrue(covered())
    }

    @Test fun insideTheSessionRobotsSettingsStayOpen() {
        code()
        backInRobot()
        assertTrue(sent(LockSignals.ACTION_FRONT))
        assertFalse(covered())
    }

    @Test fun theSessionsEndAfterRobotLeftSaysNothingOfTheFront() {
        code()
        backInRobot()
        // Another app came up over Robot's app, then the pages close.
        PhoneLockPages.paused()
        shadowOf(app).clearBroadcastIntents()
        backOutOfThePages()
        assertFalse(editEnds().single().hasExtra(LockSignals.EXTRA_AT))
    }

    @Test fun robotsAppInFrontIsTheWindowTheGateLooksAtAgain() {
        code()
        backInRobot()
        // Another way the session ends, with no word of what is in front (the pad left by Home, screen-off): the window
        // the service keeps is Robot's app now, not the pad, so the cover comes back.
        LockEngine.endEdit(ctx)
        settle()
        assertTrue(covered())
    }

    @Test fun theEndLooksAtRobotsAppWhateverWasKept() {
        code()
        backInRobot()
        // Something kept after Robot's app said it was in front (a late event from the pad), then Back out of the pages.
        event(ctx.packageName, LockSignals.CODE, SystemClock.uptimeMillis() + 10)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        backOutOfThePages()
        assertTrue(covered())
    }

    @Test fun theSessionRunningOutWithRobotInFrontBringsTheCoverBack() {
        code(endsIn = 600)
        backInRobot()
        assertFalse(covered())
        // Something kept after Robot's app said it was in front, and no refresh from :lock's own alarm (Robolectric fires
        // none): only Robot's app looking again as the session runs out brings the cover.
        event(ctx.packageName, LockSignals.CODE, SystemClock.uptimeMillis() + 10)
        // The session's end alone leaves the pad's window, the one kept.
        sessionRunsOut()
        assertFalse(covered())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        settle()
        assertTrue(covered())
    }

    @Test fun leavingRobotBeforeTheSessionRunsOutSendsNothingMore() {
        code(endsIn = 600)
        backInRobot()
        // The reader came up over Robot's app.
        PhoneLockPages.paused()
        later()
        event(reader, "$reader.Main")
        shadowOf(app).clearBroadcastIntents()
        sessionRunsOut()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        settle()
        assertFalse(sent(LockSignals.ACTION_FRONT))
        assertFalse(covered())
    }

    @Test fun aWindowThatCameUpSinceRobotSaidSoDecides() {
        val at = SystemClock.uptimeMillis()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        // The reader came up after Robot's app said it was in front, and its event was handled first: it stays open.
        event(reader, "$reader.Main", at + 10)
        assertFalse(covered())
        svc.robotInFront(at)
        idle()
        assertFalse(covered())
        // Said later than the reader's event: Robot's app is in front, and covered.
        svc.robotInFront(at + 20)
        idle()
        assertTrue(covered())
    }

    @Test fun robotsAppComingToTheFrontDuringALockIsCovered() {
        later()
        PhoneLockPages.resumed(robot)
        settle()
        assertTrue(sent(LockSignals.ACTION_FRONT))
        assertTrue(covered())
    }

    @Test fun aTieGoesToTheWindowEvent() {
        val at = SystemClock.uptimeMillis()
        event(reader, "$reader.Main", at)
        svc.robotInFront(at)
        idle()
        assertFalse(covered())
    }

    @Test fun aWindowEventDatedInTheFutureDoesntOutlastRobotsWord() {
        event(reader, "$reader.Main", SystemClock.uptimeMillis() + 60_000)
        assertFalse(covered())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        svc.robotInFront(SystemClock.uptimeMillis())
        idle()
        assertTrue(covered())
    }

    @Test fun robotsWordCountsOnlyWhileTheWindowsAreWatched() {
        // Phone lock off: no window events, so no window in front is known, and Robot's word doesn't make one.
        assertNotNull(LockStore.editLocks(ctx) { f -> f.copy(settings = f.settings.copy(on = false)) })
        LockEngine.refresh(ctx)
        settle()
        assertNull(kept())
        svc.robotInFront(SystemClock.uptimeMillis())
        idle()
        assertNull(kept())
    }

    @Test fun withNoLockOnNowRobotsAppIsntCovered() {
        // state.json says no lock is on; it may be older than :lock's own (one it couldn't write), so Robot's app says it
        // is in front anyway, and the gate decides.
        assertNotNull(LockStore.editLocks(ctx) { f -> f.copy(locks = f.locks.map { it.copy(on = false) }) })
        LockEngine.refresh(ctx)
        settle()
        assertFalse(LockEngine.snapshot!!.active)
        covered()
        shadowOf(app).clearBroadcastIntents()
        PhoneLockPages.resumed(robot)
        settle()
        assertTrue(sent(LockSignals.ACTION_FRONT))
        assertFalse(covered())
    }

    @Test fun withPhoneLockOffRobotsAppSendsNothing() {
        assertNotNull(LockStore.editLocks(ctx) { f -> f.copy(settings = f.settings.copy(on = false)) })
        LockEngine.refresh(ctx)
        settle()
        shadowOf(app).clearBroadcastIntents()
        PhoneLockPages.resumed(robot)
        settle()
        assertFalse(sent(LockSignals.ACTION_FRONT))
        assertFalse(covered())
    }

    @Test fun aStateThatCantBeReadStillSaysRobotIsInFront() {
        stateCantBeRead()
        later()
        PhoneLockPages.resumed(robot)
        settle()
        assertTrue(sent(LockSignals.ACTION_FRONT))
        assertTrue(covered())
    }

    @Test fun aStateThatCantBeReadStillEndsTheSession() {
        stateCantBeRead()
        backOutOfThePages()
        assertTrue(sent(LockSignals.ACTION_EDIT_END))
    }

    @Test fun carModeYourCarStartedDuringYourOwnLockCoversNothing() {
        assertNotNull(LockStore.editLocks(ctx) { f -> f.copy(settings = f.settings.copy(forWho = LockFor.ME)) })
        LockEngine.car(ctx, on = true, byCar = true)
        settle()
        PhoneLockPages.resumed(robot)
        settle()
        assertFalse(covered())
        LockEngine.car(ctx, on = false, byCar = true)
        settle()
    }
}
