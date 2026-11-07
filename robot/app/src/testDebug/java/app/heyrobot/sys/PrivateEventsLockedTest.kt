// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.routines.RoutineRunner
import app.heyrobot.core.BriefItem
import app.heyrobot.core.SpokenPart
import app.heyrobot.core.StepCode
import app.heyrobot.core.calendar.PrivateEdits
import app.heyrobot.core.calendar.PrivateEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Today fix (review-today-first, finding 1): a locked phone knows private events wait, without opening them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivateEventsLockedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun lock(on: Boolean) = shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(on)

    @Before fun setUp() { PrivateEventsFiles.testKeys = SoftMemoryKeys() }

    @After fun clean() {
        lock(false)
        PrivateEventsFiles.testKeys = null
        ctx.filesDir.deleteRecursively()
    }

    @Test fun lockedWithSealedEventsSaysTheyWait() {
        assertFalse(PrivateEventsFiles.now(ctx).locked)
        lock(true)
        assertFalse("none sealed yet", TodaySource.input(ctx).privateLocked)
        lock(false)
        val t = System.currentTimeMillis() + 3_600_000L
        PrivateEventsFiles.vault(ctx).update { f, now -> PrivateEdits.save(f, PrivateEvent(PrivateEdits.newId(), "Counsellor", t, t + 3_600_000L, false, "", 1), now) }
        assertFalse(TodaySource.input(ctx).privateLocked)
        assertEquals(1, TodaySource.input(ctx).events?.size)
        lock(true)
        val input = TodaySource.input(ctx)
        assertTrue(input.privateLocked)
        assertTrue(input.events.isNullOrEmpty())
    }

    @Test fun aLockedBriefLeavesItsCalendarPlaceForTheUnlock() {
        fun part(item: BriefItem, text: String) = SpokenPart(listOf(text), true, StepCode.BRIEF, item)
        val held = listOf(part(BriefItem.CALENDAR, "Unlock your phone to hear your private events."), part(BriefItem.REMINDERS, "Call the bank."))
        assertEquals(held, RoutineRunner.calendarLater(held, later = false))
        val later = RoutineRunner.calendarLater(held, later = true)
        assertEquals(listOf(RoutineRunner.CALENDAR_LATER, held[1]), later)
        assertTrue(later[0].lines.isEmpty())
    }
}
