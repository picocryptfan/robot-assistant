// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.ui.settings.PhoneLockPages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAppTask
import java.io.File

/**
 * Phone lock §8.2: the 5-minute edit session the code opens ends when you leave. Robot's app leaving the screen (Home,
 * Recents, the power button, another app's window) sends the end to :lock, unless Robot's own code pad is what covers
 * it; the code pad left without finishing ends the session itself, and a pad for a new code closes. Screen-off, in
 * :lock, is LockGateAgainTest's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditSessionLeaveTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val app get() = ctx as Application

    @Before fun setUp() {
        shadowOf(app).clearBroadcastIntents()
    }

    @After fun tearDown() {
        File(LockStore.dir(ctx), LockStore.STATE).delete()
    }

    private fun openSession() {
        LockEngine.openEdit(ctx)
        assertTrue(LockStore.readState(ctx).editOpen(System.currentTimeMillis()))
    }

    private fun endSent(): Boolean = shadowOf(app).broadcastIntents.any { it.action == LockSignals.ACTION_EDIT_END && it.component?.className == LockSignals.RECEIVER }

    /** [a]'s task, with [top] at its top, as ActivityManager's app tasks show it. */
    private fun taskTop(a: Activity, top: String, taskId: Int = a.taskId) {
        val task = ShadowAppTask.newInstance()
        Shadow.extract<ShadowAppTask>(task).setTaskInfo(ActivityManager.RecentTaskInfo().apply {
            this.taskId = taskId
            topActivity = ComponentName(ctx.packageName, top)
        })
        shadowOf(ctx.getSystemService(ActivityManager::class.java)).setAppTasks(listOf(task))
    }

    private fun waitForEdit(open: Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (LockStore.readState(ctx).editOpen(System.currentTimeMillis()) != open && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    @Test fun leavingRobotEndsTheSession() {
        openSession()
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        taskTop(a, "app.heyrobot.ui.MainActivity")
        PhoneLockPages.left(a)
        assertTrue(endSent())
    }

    @Test fun withNoSessionNothingIsSent() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        PhoneLockPages.left(a)
        assertFalse(endSent())
    }

    @Test fun robotsCodePadOverItKeepsTheSession() {
        openSession()
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        // The pad opened from Settings › Phone lock, in Robot's task: not leaving.
        taskTop(a, LockSignals.CODE)
        PhoneLockPages.left(a)
        assertFalse(endSent())
        // A pad at the top of another task (the cover's) doesn't cover this window: leaving.
        taskTop(a, LockSignals.CODE, taskId = a.taskId + 1)
        PhoneLockPages.left(a)
        assertTrue(endSent())
    }

    @Test fun aPadLeftEndsTheSessionAndAPadForANewCodeCloses() {
        openSession()
        val pad = Robolectric.buildActivity(LockCodeActivity::class.java, LockSignals.code(ctx, LockSignals.PURPOSE_SET)).setup()
        assertFalse(pad.get().isFinishing)
        // Home, Recents or the power button: stopped without finishing.
        pad.pause().stop()
        assertTrue(pad.get().isFinishing)
        waitForEdit(open = false)
        assertEquals(0L, LockStore.readState(ctx).edit)
    }

    @Test fun aPadThatFinishesKeepsTheSession() {
        openSession()
        val pad = Robolectric.buildActivity(LockCodeActivity::class.java, LockSignals.code(ctx, LockSignals.PURPOSE_SET)).setup()
        // Back, Cancel or a code set: the pad finishes and Settings › Phone lock comes back, the session still open.
        pad.get().finish()
        pad.pause().stop().destroy()
        Thread.sleep(300)
        assertTrue(LockStore.readState(ctx).editOpen(System.currentTimeMillis()))
    }

    @Test fun aRotationIsntLeaving() {
        openSession()
        val pad = Robolectric.buildActivity(LockCodeActivity::class.java, LockSignals.code(ctx, LockSignals.PURPOSE_SET)).setup()
        pad.recreate()
        Thread.sleep(300)
        assertTrue(LockStore.readState(ctx).editOpen(System.currentTimeMillis()))
    }
}
