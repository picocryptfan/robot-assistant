// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.lock.EndEarly
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFileRead
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.LockState
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime

/**
 * 1.1 (WP9b): Settings › Phone lock with the menus in Québec French (strings_lock.xml, core's LockStrings), two locks set
 * up, access allowed. Its words still fit at 48 dp targets, and phone_lock_fr is the French screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-rCA-$PHONE")
class PhoneLockFrenchTest {
    @get:Rule val rule = createComposeRule()

    private val bedtime = PhoneLock("lk_dodo0001", "Dodo", LockRule.Time(TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0))))
    private val morning = PhoneLock("lk_matin001", "Matin", LockRule.Alarm(minutes = 30))
    private val file = LockFile(LockSettings(on = true, endEarly = EndEarly.WAIT), listOf(bedtime, morning))
    private val data = PhoneLockData(LockFileRead.Ok(file), LockState(), access = true, notifications = true, usage = true)
    private val act = PhoneLockActions(open = {}, edit = { true }, askCode = {}, removeCode = {}, startOver = {})

    @Test fun thePageInFrench() {
        rule.show { PhoneLockScreen(data, act, SnackbarHostState(), onBack = {}) }
        rule.onNodeWithText("Dodo").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("phone_lock_fr")
    }
}
