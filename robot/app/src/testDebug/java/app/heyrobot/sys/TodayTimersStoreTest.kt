// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.TodayTimers
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
 * fix-today-timers: today_timers.json (Today's record of the timers Robot set) goes with your data, as robot_timers.json
 * does, and once none of its timers is still shown. Before the fix it went only when a timer was cancelled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayTimersStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val record get() = File(ctx.noBackupFilesDir, "today_timers.json")
    private val now = System.currentTimeMillis()

    private fun write(vararg ends: Long) = record.writeText(TodayTimers.encode(ends.toList()))

    @Before fun empty() = RobotTimers.clear(ctx)

    @Test fun historyDeleteAllRemovesTheRecord() {
        TodaySource.timerSet(ctx, 300)
        assertTrue(record.exists())
        ConversationStore.deleteAll(ctx)
        assertFalse(record.exists())
    }

    @Test fun memoryDeleteAllRemovesTheRecord() {
        TodaySource.timerSet(ctx, 300)
        assertTrue(PhoneMemoryStore(ctx).deleteAll())
        assertFalse(record.exists())
    }

    /** Memory's Start over (only on an unreadable memory file) and a restore's Replace of History call the same clear. */
    @Test fun clearRemovesTheRecord() {
        TodaySource.timerSet(ctx, 300)
        RobotTimers.clear(ctx)
        assertFalse(record.exists())
    }

    @Test fun endedRecordIsRemovedOnTheNextRead() {
        write(now - 60_000, now - 1_000)
        assertEquals(emptyList<Long>(), TodaySource.timers(ctx, now))
        assertFalse(record.exists())
    }

    @Test fun runningTimerKeepsTheRecord() {
        write(now - 60_000, now + 60_000)
        assertEquals(listOf(now + 60_000), TodaySource.timers(ctx, now))
        assertTrue(record.exists())
    }

    @Test fun halfWrittenRecordStays() {
        record.writeText("{\"v\":1,\"ends\":[")
        assertEquals(emptyList<Long>(), TodaySource.timers(ctx, now))
        assertTrue(record.exists())
    }
}
