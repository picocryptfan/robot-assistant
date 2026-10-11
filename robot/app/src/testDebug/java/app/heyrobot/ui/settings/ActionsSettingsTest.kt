package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.heyrobot.core.Approval
import app.heyrobot.core.LockScreen
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.showSettings
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Actions: a row per action with its level, a radio dialog to change it, and the nearest place's two settings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ActionsSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()

    private fun open(start: RobotSettings = RobotSettings()): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> ActionsSettingsScreen(s, Snapshot(), u, log.actions, SnackbarHostState(), onBack = {}) }
        return host
    }

    @Test fun everyActionHasARowThatSaysItsLevel() {
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        for (t in ToolName.entries.filter { !it.internal }) {
            rule.scrollToText(t.title)
            val want = approvalSummary(t, RobotSettings().approval(t), t in LockScreen.DEFAULT)
            rule.onRow(t.title).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, want))
            assertEquals(t.title, emptyList<String>(), rule.touchTargetProblems())
        }
        assertEquals("Just do it · works locked", approvalSummary(ToolName.SET_TIMER, Approval.AUTO, true))
        assertEquals("Just do it · uses the internet", approvalSummary(ToolName.WEATHER, Approval.AUTO, false))
    }

    @Test fun aLevelIsPickedInARadioDialogThatCloses() {
        val host = open()
        rule.scrollToText(ToolName.SEND_TEXT.title)
        rule.onRow(ToolName.SEND_TEXT.title).performClick()
        rule.onNode(isDialog()).assertExists()
        rule.inDialog("Ask first").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.inDialog("Just do it").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(RobotSettings(approvals = RobotSettings.DEFAULT_APPROVALS + (ToolName.SEND_TEXT to Approval.AUTO)), host.last)
    }

    @Test fun theNearestPlaceOnline() {
        val host = open()
        rule.scrollToText("Find the nearest place online")
        rule.onRow("Find the nearest place online").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, NearestOnline.ASK.title)).performClick()
        rule.inDialog(NearestOnline.NEVER.title).performClick()
        assertEquals(NearestOnline.NEVER, host.last.nearestOnline)
        assertTrue(host.last.nearestOnlineAt > 0)
    }

    // Robolectric settles with a focused text field in a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theSearchServerIsCheckedBeforeItIsSaved() {
        val host = open()
        rule.scrollToText("Search server")
        rule.onRow("Search server").assert(hasText("OpenStreetMap's Nominatim", substring = true)).performClick()
        val field = rule.onNode(hasText("Nominatim server") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        field.performTextReplacement("http://nominatim.example.org")
        rule.inDialog("Save").assertIsNotEnabled()
        rule.inDialog("Use an https:// address", substring = true).assertExists()
        field.performTextReplacement("nominatim.example.org")
        rule.inDialog("Save").assertIsEnabled().performClick()
        assertEquals("https://nominatim.example.org", host.last.nearestServer)
        rule.scrollToText("Reset to OpenStreetMap's")
        rule.onRow("Reset to OpenStreetMap's").performClick()
        assertEquals("", host.last.nearestServer)
    }

    @Test fun light() {
        open()
        rule.shot("actions_light")
    }

    @Test fun theDialog() {
        open()
        rule.scrollToText(ToolName.SEND_TEXT.title)
        rule.onRow(ToolName.SEND_TEXT.title).performClick()
        rule.shot("actions_dialog")
    }
}
