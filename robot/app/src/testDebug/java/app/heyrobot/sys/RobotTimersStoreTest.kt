// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.TimerAsk
import app.heyrobot.ui.settings.PhoneMemoryStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * fix-timers-store: robot_timers.json (the timers Robot asked the clock to set) goes with your data and when it holds
 * nothing still worth knowing. Before the fix it was pruned only when a new timer was added, and only tests cleared it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotTimersStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val record get() = File(ctx.filesDir, "robot_timers.json")
    private val now = System.currentTimeMillis()

    @Before fun empty() = RobotTimers.clear(ctx)

    @Test fun historyDeleteAllRemovesTheRecord() {
        RobotTimers.set(ctx, 300, now - 60_000)
        assertTrue(record.exists())
        ConversationStore.deleteAll(ctx)
        assertFalse(record.exists())
    }

    @Test fun memoryDeleteAllRemovesTheRecord() {
        RobotTimers.set(ctx, 300, now - 60_000)
        assertTrue(PhoneMemoryStore(ctx).deleteAll())
        assertFalse(record.exists())
    }

    @Test fun staleRecordIsRemovedOnTheNextRead() {
        RobotTimers.set(ctx, 60, now - TimerAsk.KEEP_MS - 120_000)
        assertTrue(record.exists())
        assertEquals(emptyList<Any>(), RobotTimers.load(ctx))
        assertFalse(record.exists())
    }

    @Test fun staleRecordIsNotWrittenBack() {
        RobotTimers.set(ctx, 60, now - TimerAsk.KEEP_MS - 120_000)
        RobotTimers.cancelAsked(ctx, now)
        assertFalse(record.exists())
    }

    @Test fun runningAndRecentTimersSurvive() {
        RobotTimers.set(ctx, 60, now - TimerAsk.KEEP_MS + 600_000)
        RobotTimers.set(ctx, 300, now - 60_000)
        assertEquals(2, RobotTimers.load(ctx).size)
        assertTrue(record.exists())
        assertEquals(1, TimerAsk.running(RobotTimers.load(ctx), now).size)
    }
}
