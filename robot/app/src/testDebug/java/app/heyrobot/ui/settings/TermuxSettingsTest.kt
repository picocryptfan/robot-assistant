package app.heyrobot.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.termux.TermuxIdentity
import app.heyrobot.core.termux.TermuxPackage
import app.heyrobot.core.termux.TermuxScriptInfo
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.termux.TermuxSetupStatus
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Termux (0.9, B5.1, B6): off by default, the switch held off until every set-up line is green, each line's
 * button (F-Droid, Allow, Copy, Test, Create it, Trust with its warning), your scripts' levels, and the screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TermuxSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ArrayList<String>()
    private val saved = ArrayList<TermuxSettings>()

    private val fdroid = TermuxPackage(true, "0.118.3", listOf(TermuxIdentity.F_DROID), hasRunCommandService = true, permissionOwner = "com.termux")
    private val fresh = TermuxUi(check = TermuxIdentity.check(fdroid))
    private val ready = TermuxUi(
        settings = TermuxSettings(trustedSigner = TermuxIdentity.F_DROID),
        check = TermuxIdentity.check(fdroid, TermuxIdentity.F_DROID), granted = true,
        status = TermuxSetupStatus(testAt = 1, testOk = true, testMs = 340, timeout = true, signer = TermuxIdentity.F_DROID, listAt = 2,
            scripts = listOf(TermuxScriptInfo("backup", "Copies my notes to the server"), TermuxScriptInfo("hello", "Says hello"))),
    )

    private fun open(start: TermuxUi) {
        var ui by mutableStateOf(start)
        val act = TermuxPageActions(
            save = { saved += it; ui = ui.copy(settings = it) }, askPermission = { log += "allow" }, openFdroid = { log += "fdroid" },
            openAppInfo = { log += "appInfo" }, copyLine = { log += "copy" }, run = { log += "run $it" },
        )
        rule.show { TermuxSettingsScreen(ui, act, onBack = { log += "back" }) }
    }

    @Test fun nothingRunsUntilTheChecklistIsGreen() {
        open(fresh)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNode(hasText("Let Robot use Termux") and isToggleable()).assertIsOff().assertIsNotEnabled()
        rule.onNodeWithText("Termux 0.118.3 from F-Droid").assertExists()
        rule.onNodeWithContentDescription("Allow Robot to run commands in Termux").performClick()
        rule.scrollToText("Copy", substring = false)
        rule.onNodeWithContentDescription("Copy the line to paste in Termux").performClick()
        rule.onNodeWithText(app.heyrobot.core.termux.TermuxSetup.ALLOW_LINE).assertExists()
        // No Test before the permission: Android would refuse it.
        rule.onNodeWithContentDescription("Test Termux").assertDoesNotExist()
        rule.scrollToText("Trust this Termux")
        rule.onNodeWithContentDescription("Trust this Termux").performClick()
        rule.inDialog("Trust").performClick()
        assertEquals(listOf("allow", "copy"), log)
        assertEquals(TermuxIdentity.F_DROID, saved.last().trustedSigner)
        assertTrue(saved.none { it.enabled })
    }

    @Test fun onceGreenTheSwitchAndTheSettings() {
        open(ready)
        rule.onNode(hasText("Let Robot use Termux") and isToggleable()).performClick()
        assertTrue(saved.last().enabled)
        rule.onNodeWithContentDescription("Test Termux").performClick()
        rule.onNodeWithContentDescription("Refresh the list of your scripts").performClick()
        rule.inDialog("Run").performClick()
        assertEquals(listOf("run test", "run list"), log)
        rule.scrollToText("Your scripts")
        rule.onAllNodes(hasText("backup"))[0].performClick()
        rule.inDialog("Just do it").performClick()
        assertEquals(app.heyrobot.core.termux.ScriptLevel.AUTO, saved.last().script("backup"))
        rule.scrollToText("Read-only commands without a card")
        rule.onNode(hasText("Read-only commands without a card") and isToggleable()).assertIsOff()
    }

    @Test fun light() {
        open(fresh)
        rule.shot("termux_settings_setup")
    }

    @Test fun readyLight() {
        open(ready.copy(settings = ready.settings.copy(enabled = true)))
        rule.shot("termux_settings_ready")
    }
}
