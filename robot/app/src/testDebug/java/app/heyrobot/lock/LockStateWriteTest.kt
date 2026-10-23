// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.lock.ActiveLock
import app.heyrobot.core.lock.LockCodec
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.LockState
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.core.lock.RecordEvent
import app.heyrobot.sys.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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

/**
 * :lock's state.json when the write fails (storage full; here, a lock folder nothing can be written in): the state the
 * engine worked out is kept in :lock, so a lock that came due and a lock by voice are both on; :lock's own reads see it;
 * "Phone lock couldn't save its state" is up; the next refresh tries the write again and, once it works, writes the state
 * with its record and takes the notice down. And a second lock by voice, through the engine, only ever makes the one on
 * longer, recorded as such.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockStateWriteTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = LockStore.dir(ctx)
        // Phone lock on, for you, with a Time of day lock whose window is open now.
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        val homework = PhoneLock("lk_hw00", "Homework", LockRule.Time(TimeWindow(now.minusHours(1), now.plusHours(1)), DayOfWeek.entries.toSet()))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true), listOf(homework)) })
    }

    @After fun tearDown() {
        dir.setWritable(true)
        LockEngine.refresh(ctx)
        File(dir, LockStore.STATE).delete()
        File(dir, LockStore.LOCKS).delete()
    }

    private fun stored(): LockState = LockCodec.decodeState(File(dir, LockStore.STATE).takeIf { it.isFile }?.readText()) ?: LockState()

    private fun unsavedNotice(): Boolean {
        val title = LockStrings.noticeUnsaved().render(AppLanguage.langs(ctx))
        return shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications
            .any { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title }
    }

    @Test fun aFailedWriteKeepsTheStateAndTriesAgain() {
        dir.setWritable(false)
        assumeFalse("file permissions don't apply to this user", dir.canWrite())
        val end = System.currentTimeMillis() + 3_600_000
        val s = LockEngine.refresh(ctx, voiceEnd = end)
        // Both locks are on in :lock, though nothing reached state.json.
        assertEquals(setOf("lk_hw00", ActiveLock.VOICE), s.state.active.map { it.lock }.toSet())
        assertTrue(LockStore.unsavedState)
        assertTrue(stored().active.isEmpty())
        // :lock's reads (the code pad's tries, the next edit) see that state, and the next refresh doesn't fall back.
        assertEquals(s.state, LockStore.readState(ctx))
        assertEquals(s.state.active, LockEngine.refresh(ctx).state.active)
        assertTrue(unsavedNotice())
        // Room again: the next refresh writes it, with its record, and the notice goes.
        dir.setWritable(true)
        val after = LockEngine.refresh(ctx)
        assertFalse(LockStore.unsavedState)
        assertEquals(after.state.active, stored().active)
        assertEquals(setOf("lk_hw00", ActiveLock.VOICE), stored().record.filter { it.e == RecordEvent.STARTED || it.e == RecordEvent.COULD_NOT_START }.map { it.lock }.toSet())
        assertFalse(unsavedNotice())
    }

    @Test fun aSecondLockByVoiceOnlyEverMakesItLonger() {
        val now = System.currentTimeMillis()
        val first = LockEngine.refresh(ctx, voiceEnd = now + 2 * 3_600_000).state.active.single { it.voice }
        // Accessibility is off here, so it is recorded as "couldn't start" (with it on, "started").
        assertTrue(stored().record.any { it.lock == ActiveLock.VOICE && it.e == RecordEvent.COULD_NOT_START })
        // A shorter one changes nothing and records nothing.
        val before = stored()
        LockEngine.refresh(ctx, voiceEnd = now + 60_000)
        assertEquals(first, stored().active.single { it.voice })
        assertEquals(before.record, stored().record)
        // A longer one moves its end, from the same start, and What happened says so with the new end.
        LockEngine.refresh(ctx, voiceEnd = now + 3 * 3_600_000)
        val longer = stored().active.single { it.voice }
        assertEquals(first.start, longer.start)
        assertEquals(now + 3 * 3_600_000, longer.end)
        val line = stored().record.last()
        assertEquals(RecordEvent.LONGER, line.e)
        assertEquals(ActiveLock.VOICE, line.lock)
        assertEquals(180, line.n)
    }
}
