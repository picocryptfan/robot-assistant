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
import androidx.compose.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


/** Review L5: Quick access draws nothing until this phone's facts are read, then every button at once. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class QuickReadTest {
    @get:Rule val rule = createComposeRule()

    // Every frame drawn has this phone's buttons: none from before the read (Robot to Robot and Skills popping in after).
    @Test fun nothingUntilReadThenEveryButtonAtOnce() {
        val drawn = ArrayList<List<QuickItem>>()
        rule.show(false) {
            QuickRead(QuickItem.DEFAULT, { QuickPhone(link = true, skills = 2, speech = true) }) { b ->
                drawn += b.map { it.item }
                Text(b.joinToString { it.item.name })
            }
        }
        rule.waitUntil(10_000) { drawn.isNotEmpty() }
        assertTrue(drawn.all { QuickItem.LINK in it && QuickItem.SKILLS in it })
    }
}
