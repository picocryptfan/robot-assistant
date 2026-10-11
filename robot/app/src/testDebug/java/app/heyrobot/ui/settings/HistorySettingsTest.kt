package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import app.heyrobot.core.RobotSettings
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.showSettings
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › History, Advanced and About: small pages, each row doing what it says. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class HistorySettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()

    @Test fun theMainSwitchSavesAndTurnsThePicturesRowOff() {
        val host = SettingsHost()
        rule.showSettings(host) { s, u -> HistorySettingsScreen(s, u, log.actions, SnackbarHostState(), onBack = {}) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Screenshots and photos").assertIsEnabled().performClick()
        assertEquals(listOf("openPage ${Page.Safety}"), log)
        rule.onRow("Save conversations").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        assertEquals(RobotSettings(saveHistory = false), host.last)
        rule.onRow("Screenshots and photos").assertIsNotEnabled()
        // Deleting what is saved works either way, after a confirmation.
        rule.onRow("Delete all conversations").assertIsEnabled().performClick()
        rule.inDialog("Cancel").performClick()
        rule.onRow("Delete all conversations").performClick()
        rule.inDialog("Delete all").performClick()
        assertEquals("deleteAllConversations", log.last())
    }

    @Test fun advancedOpensTheFixesAndTheLog() {
        rule.show { AdvancedSettingsScreen(log.actions, onBack = {}) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("ADB fixes").performClick()
        rule.onRow("Voice log").performClick()
        assertEquals(listOf("openPage ${Page.AdbFixes}", "openPage ${Page.VoiceLog}"), log)
    }

    @Test fun aboutHasTheVersionAndTheLicences() {
        rule.show { AboutSettingsScreen(log.actions, onBack = {}) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Licences").performClick()
        assertEquals(listOf("openPage ${Page.Licences}"), log)
    }
}
