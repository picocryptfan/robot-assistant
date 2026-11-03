// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.R
import app.heyrobot.core.Attachment
import app.heyrobot.core.ConvMode
import app.heyrobot.core.ConversationCommand
import app.heyrobot.core.ConversationRouter
import app.heyrobot.core.LookSettings
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.setShotContent
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.2.1: conversation mode at the top of the card, as two people talking. The button says its own label for you, so that
 * label in each language has to be a command the router reads; with the phone lock and lock screen's rules in
 * PanelConversationMode, as when you say it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ConversationButtonTest {
    @get:Rule val rule = createComposeRule()

    private val label get() = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.panel_conversation_mode)
    private val actions = RecordingActions()

    private fun show(st: PanelState) =
        rule.setShotContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) { AssistantPanel(st, actions) {} } }

    private fun tapsAndRoutes(words: String) {
        assertEquals(words, label)
        assertEquals(ConversationCommand.Start(ConvMode.TRANSCRIBE), ConversationRouter.route(label, false))
        show(PanelFixtures.idle)
        rule.onNode(hasContentDescription(label)).assertIsEnabled().performClick()
        assertEquals(listOf("send $label"), actions.calls)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun english() { tapsAndRoutes("Transcribe this conversation"); rule.shot("panel_conversation_button") }

    @Test @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    fun french() { tapsAndRoutes("Transcrire cette conversation"); rule.shot("panel_conversation_button_fr") }

    /** Five buttons don't fit beside "Robot" here: they take their own line, all still 48 dp. */
    @Test @Config(fontScale = 2f)
    fun atTwiceTheTextSize() { tapsAndRoutes("Transcribe this conversation"); rule.shot("panel_conversation_button_font200") }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun onANarrowPhone() { tapsAndRoutes("Transcribe this conversation"); rule.shot("panel_conversation_button_narrow") }

    @Test fun notWhileATurnRuns() {
        show(PanelFixtures.idle.copy(phase = Phase.THINKING))
        rule.onNode(hasContentDescription(label)).assertIsNotEnabled()
    }

    @Test fun notWithAPictureWaitingForYourQuestion() {
        show(PanelFixtures.idle.copy(draft = Draft(Attachment.PHOTO, null)))
        rule.onNode(hasContentDescription(label)).assertIsNotEnabled()
    }
}
