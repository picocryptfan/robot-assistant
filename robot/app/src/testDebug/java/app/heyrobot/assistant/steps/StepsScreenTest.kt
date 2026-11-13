// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.steps

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.ui.testing.PHONE
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.heyrobot.R
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.heyrobot.core.steps.StepWord
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText

/** Step by step's window: the microphone mark shows whenever the ear has the microphone, and only then; buttons are words. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class StepsScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test fun microphoneShownWhileListening() {
        val taps = ArrayList<StepWord>()
        var ui by androidx.compose.runtime.mutableStateOf(StepVoice.Ui(on = true, index = 1, total = 3, text = "Bake 25 minutes.", offerS = 1500, listening = true))
        rule.setContent { StepsScreen(ui) { taps += it } }
        assertEquals(1, rule.onAllNodesWithContentDescription(str(R.string.read_steps_listening)).fetchSemanticsNodes().size)
        rule.onNodeWithText("Bake 25 minutes.").assertExists()
        rule.onNodeWithText(str(R.string.read_steps_next)).performClick()
        rule.onNodeWithText(str(R.string.read_steps_timer)).performClick()
        assertEquals(listOf(StepWord.NEXT, StepWord.YES), taps)
        ui = ui.copy(listening = false, offerS = null)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithContentDescription(str(R.string.read_steps_listening)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText(str(R.string.read_steps_timer)).fetchSemanticsNodes().size)
    }
}
