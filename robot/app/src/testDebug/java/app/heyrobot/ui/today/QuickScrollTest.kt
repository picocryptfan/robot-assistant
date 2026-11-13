// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.R
import app.heyrobot.core.QuickButton
import app.heyrobot.core.QuickItem
import app.heyrobot.core.QuickItems
import app.heyrobot.core.QuickPhone
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review L5: at twice the text size (one column, the buttons fill the screen) a drag that starts on a button scrolls the
 * page; press and hold first still moves the button.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, fontScale = 2f)
class QuickScrollTest {
    @get:Rule val rule = createComposeRule()
    private val buttons = QuickItems.shown(QuickItem.DEFAULT, QuickPhone(link = true, skills = 2, speech = true, translationToGet = true))
    private fun top(name: String) = rule.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot.top

    @Test fun aDragScrollsAtTwiceTheTextSize() {
        rule.show(false) { QuickHome(buttons, Modifier.fillMaxSize(), onOpen = {}, onHistory = {}, onGettingStarted = {}, onMove = { _, _ -> }) }
        val before = top("Talk to Robot")
        rule.onNodeWithContentDescription("Car mode").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue("moved ${before - top("Talk to Robot")} px", top("Talk to Robot") < before - 100f)
    }
}
