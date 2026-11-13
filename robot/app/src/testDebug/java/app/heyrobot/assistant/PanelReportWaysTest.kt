// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.heyrobot.core.LookSettings
import app.heyrobot.ui.classic.ClassicHeader
import app.heyrobot.ui.classic.OrbMood
import app.heyrobot.ui.common.TurnActions
import app.heyrobot.ui.common.TurnUi
import app.heyrobot.ui.common.TurnView
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestRule
import org.robolectric.internal.TimeLimitedStatement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** RP3, the ways into the bug report card: the panel's bug button, and a reply pressed and held (the panel and History). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelReportWaysTest {
    // Review M3: a hang fails this test after 60 s; the Gradle timeout (build.gradle) is the backstop.
    @get:Rule(order = -2) val timeout = TestRule { base, _ -> TimeLimitedStatement(60_000, base) }
    @get:Rule val rule = createComposeRule()

    @Test fun theBugButtonIsThereOnlyWithATurn() {
        var taps = 0
        var on by mutableStateOf(true)
        rule.setContent {
            RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) {
                ClassicHeader(OrbMood.IDLE, "On this phone, private", false, false, {}, {}, {}, {}, onReport = if (on) ({ taps++ }) else null)
            }
        }
        // In the header's menu with Settings, so the panel doesn't grow for it (PanelHeaderFitTest pins the height).
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Report a problem with the last answer").performClick()
        assertEquals(1, taps)
        rule.onNodeWithText("Report a problem with the last answer").assertDoesNotExist()
        on = false
        rule.waitForIdle()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Report a problem with the last answer").assertIsNotEnabled()
        rule.onNodeWithText("Settings").assertIsEnabled()
    }

    @Test fun pressAndHoldAReplyToReportIt() {
        val ids = mutableListOf<String>()
        val report = TurnActions(report = { ids += it })
        rule.setContent {
            RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) {
                Column { TurnView(TurnUi("u1", true, "what's 2 plus 2"), report); TurnView(TurnUi("a1", false, "It's 5."), report) }
            }
        }
        rule.onNodeWithText("It's 5.").performTouchInput { longClick() }
        rule.onNodeWithText("Report a problem with this answer").performClick()
        assertEquals(listOf("a1"), ids)
        rule.onNodeWithText("what's 2 plus 2", substring = true).performTouchInput { longClick() }
        rule.onNodeWithText("Report a problem with this answer").assertDoesNotExist()
        assertEquals(listOf("a1"), ids)
    }
}
