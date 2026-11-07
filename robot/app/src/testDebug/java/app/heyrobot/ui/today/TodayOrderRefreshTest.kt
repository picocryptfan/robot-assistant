// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.HomeSettings
import app.heyrobot.core.TodayMove
import app.heyrobot.core.TodayPart
import app.heyrobot.sys.HomeSettingsStore
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * today-fix-d (phone-today-a H4 2/3): after "put my calendar first" the Today page kept its old order until you left the tab.
 * The panel only takes the focus from the app, never pauses it, so the page's read on resume never came. The page now
 * follows the file: a write by either process changes its stamp, and the page reads it again at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = PHONE)
class TodayOrderRefreshTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun aVoiceReorderRedrawsThePageWithoutLeavingIt() {
        HomeSettingsStore.update(ctx) { HomeSettings(today = true, order = listOf(TodayPart.NOTE, TodayPart.CALENDAR, TodayPart.LISTS)) }
        val changes = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        rule.show {
            // Resumed the whole time, as behind the panel.
            val home by rememberHomeSettings({ HomeSettingsStore.current(ctx) }, active = true, changes = changes) { HomeSettingsStore.current(ctx) }
            Text("first: " + home.order.first().key)
        }
        rule.onNodeWithText("first: note").assertExists()
        // :voice moves the calendar first (PanelRoutines.arrange); the stamp watch says the file changed.
        HomeSettingsStore.update(ctx) { it.with(TodayPart.CALENDAR, TodayMove.FIRST) }
        changes.tryEmit(1)
        rule.waitForIdle()
        rule.onNodeWithText("first: calendar").assertExists()
    }

    @Test fun theStampChangesWithEachWrite() {
        HomeSettingsStore.update(ctx) { HomeSettings(today = true) }
        val one = HomeSettingsStore.stamp(ctx)
        Thread.sleep(20)
        HomeSettingsStore.update(ctx) { it.with(TodayPart.NOTE, TodayMove.FIRST) }
        assertNotEquals(one, HomeSettingsStore.stamp(ctx))
    }
}
