// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.heyrobot.core.NoteHeld
import app.heyrobot.core.TodayPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** fix-seal-lows-c 6: Today's one more read of a note busy just now: once per opening and hold, and never while the page is away. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayHeldRetryTest {
    @get:Rule val rule = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
    private val owner = Owner()
    private var reads = 0
    private var open by mutableIntStateOf(1)
    private var held by mutableStateOf<NoteHeld?>(NoteHeld.NOT_NOW)

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent { HeldRetry(open, held, owner.lifecycle) { reads++ } }
    }
    private fun pass(ms: Long) = rule.mainClock.advanceTimeBy(ms)

    @Test fun readsOnceMoreAfterTheWaitAndOnlyOnce() {
        show()
        pass(TodayPage.HELD_RETRY - 1_000)
        assertEquals(0, reads)
        pass(2_000)
        assertEquals(1, reads)
        pass(10 * TodayPage.HELD_RETRY)
        assertEquals(1, reads)
    }

    @Test fun notWhileThePageIsAway() {
        show()
        pass(1_000)
        owner.registry.currentState = Lifecycle.State.CREATED
        pass(2 * TodayPage.HELD_RETRY)
        assertEquals(0, reads)
    }

    @Test fun aNewOpeningReadsAgainAndNoHoldNever() {
        show()
        pass(TodayPage.HELD_RETRY + 1_000)
        assertEquals(1, reads)
        rule.runOnIdle { open++; Snapshot.sendApplyNotifications() }
        pass(TodayPage.HELD_RETRY + 1_000)
        assertEquals(2, reads)
        rule.runOnIdle { held = null; Snapshot.sendApplyNotifications() }
        pass(2 * TodayPage.HELD_RETRY)
        assertEquals(2, reads)
    }
}
