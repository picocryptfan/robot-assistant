// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.heyrobot.core.termux.FdroidLatest
import app.heyrobot.core.termux.GitHubLatest
import app.heyrobot.core.termux.TermuxIdentity
import app.heyrobot.core.termux.TermuxUpdateState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * 1.0: Trust records the version; the update lock's Trust this version, another signer's Set up again, the public test
 * key's way to F-Droid and its "Run anyway" (a tap and a confirmation), and "Termux updates" with Check now.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TermuxSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ArrayList<String>()
    private val saved = ArrayList<TermuxSettings>()

    private val may2025 = 1_747_950_000_000L
    private val fdroid = TermuxPackage(true, "0.118.3", listOf(TermuxIdentity.F_DROID), hasRunCommandService = true, permissionOwner = "com.termux",
        versionCode = 1002, lastUpdateTime = may2025)
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
            checkUpdates = { log += "check" }, trustLog = { log += "trust ${it.name}" },
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
        assertEquals(listOf("allow", "copy", "trust TRUSTED"), log)
        assertEquals(TermuxIdentity.F_DROID, saved.last().trustedSigner)
        // 1.0: with its version, so a later update pauses Termux until you trust it.
        assertEquals(1002L, saved.last().trustedVersionCode)
        assertEquals(may2025, saved.last().trustedUpdatedAt)
        assertTrue(saved.none { it.enabled })
    }

    @Test fun aNewVersionAsksYouToTrustIt() {
        val newer = fdroid.copy(versionName = "0.119.0", versionCode = 1030, lastUpdateTime = may2025 + 86_400_000)
        open(ready.copy(check = TermuxIdentity.check(newer, ready.settings.trustedSigner, trusted = ready.settings.trusted())))
        rule.onNodeWithText("Termux changed to 0.119.0 (F-Droid). Check that you updated it yourself, then tap Trust this version.").assertExists()
        // The Test waits: nothing runs while it is paused.
        rule.onNodeWithContentDescription("Test Termux").assertDoesNotExist()
        rule.onAllNodes(hasContentDescription("Trust this version of Termux"))[0].performClick()
        rule.inDialog("Trust this version").performClick()
        assertEquals(1030L, saved.last().trustedVersionCode)
        assertEquals("0.119.0", saved.last().trustedVersion)
        assertEquals(listOf("trust VERSION"), log)
    }

    @Test fun anotherSignerIsSetUpAgain() {
        val github = fdroid.copy(signers = listOf(TermuxIdentity.GITHUB))
        open(ready.copy(settings = ready.settings.copy(enabled = true), check = TermuxIdentity.check(github, ready.settings.trustedSigner, trusted = ready.settings.trusted())))
        rule.onNodeWithText("Termux is signed with another key than the one you trusted", substring = true).assertExists()
        rule.onAllNodes(hasContentDescription("Set up Termux again"))[0].performClick()
        rule.inDialog("Set up again").performClick()
        assertNull(saved.last().trustedSigner)
        assertFalse(saved.last().enabled)
        assertEquals(listOf("trust SET_UP_AGAIN"), log)
    }

    @Test fun theTestKeyBuildRunsNothingUntilRunAnyway() {
        val github = fdroid.copy(signers = listOf(TermuxIdentity.GITHUB))
        val s = TermuxSettings().trust(TermuxIdentity.check(github))
        open(ready.copy(settings = s, check = TermuxIdentity.check(github, s.trustedSigner, trusted = s.trusted()),
            status = ready.status.copy(signer = TermuxIdentity.GITHUB)))
        rule.onNodeWithText("This Termux is signed with a public test key", substring = true).assertExists()
        rule.onNode(hasText("Let Robot use Termux") and isToggleable()).assertIsNotEnabled()
        rule.scrollToText("Install Termux from F-Droid, open it once", substring = true)
        // (The checklist's first line has its F-Droid button too.)
        val fdroidButtons = rule.onAllNodes(hasContentDescription("Get Termux on F-Droid"))
        fdroidButtons[fdroidButtons.fetchSemanticsNodes().size - 1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.scrollToText("Run anyway, I understand the risk")
        rule.onNode(hasText("Run anyway, I understand the risk") and isToggleable()).assertIsOff().performClick()
        // A confirmation first; Cancel keeps it off.
        rule.inDialog("Cancel").performClick()
        assertTrue(saved.isEmpty())
        rule.onNode(hasText("Run anyway, I understand the risk") and isToggleable()).performClick()
        rule.inDialog("Run anyway").performClick()
        assertTrue(saved.last().testKeyRisk)
        assertEquals(listOf("fdroid", "trust RISK_ON"), log)
    }

    /**
     * 1.0: the GitHub build says so as soon as it is installed (after Set up again, before any trust): the public test key
     * sentence and the steps to the F-Droid build, Run anyway waiting for Trust, and the Test saying why it can't run
     * instead of being hidden.
     */
    @Test fun theGitHubBuildSaysSoAsSoonAsItIsInstalled() {
        val github = fdroid.copy(signers = listOf(TermuxIdentity.GITHUB))
        open(TermuxUi(check = TermuxIdentity.check(github), granted = true))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("This Termux is signed with a public test key", substring = true).assertExists()
        rule.scrollToText("Install Termux from F-Droid, open it once", substring = true)
        rule.scrollToText("Run anyway, I understand the risk")
        rule.onNode(hasText("Run anyway, I understand the risk") and isToggleable()).assertIsOff().assertIsNotEnabled()
        rule.onNodeWithText("Trust this Termux first").assertExists()
        rule.scrollToText("Robot runs nothing in the GitHub build, not even the Test", substring = true)
        rule.onNodeWithContentDescription("Test Termux").assertDoesNotExist()
        rule.scrollToText("Trust this Termux")
        rule.onNodeWithContentDescription("Trust this Termux").assertExists()
    }

    /** 1.0: another signer that is the GitHub build: Set up again first, with the test key's sentence and the way to F-Droid already. */
    @Test fun anotherSignerThatIsTheGitHubBuildShowsTheTestKeyToo() {
        val github = fdroid.copy(signers = listOf(TermuxIdentity.GITHUB))
        open(ready.copy(check = TermuxIdentity.check(github, ready.settings.trustedSigner, trusted = ready.settings.trusted())))
        rule.onNodeWithText("Termux is signed with another key than the one you trusted", substring = true).assertExists()
        rule.onNodeWithText("This Termux is signed with a public test key", substring = true).assertExists()
        rule.scrollToText("Install Termux from F-Droid, open it once", substring = true)
        // Run anyway is for the build as trusted: not before Set up again.
        rule.onNode(hasText("Run anyway, I understand the risk") and isToggleable()).assertDoesNotExist()
        rule.scrollToText("Robot runs nothing, not even the Test, until you set Termux up again.")
        rule.onNodeWithContentDescription("Test Termux").assertDoesNotExist()
    }

    /** 1.0: with Run anyway on for the trusted GitHub build, the Test runs as with F-Droid's. */
    @Test fun withRunAnywayTheGitHubBuildIsTested() {
        val github = fdroid.copy(signers = listOf(TermuxIdentity.GITHUB))
        val s = TermuxSettings().trust(TermuxIdentity.check(github)).copy(testKeyRisk = true)
        open(TermuxUi(settings = s, check = TermuxIdentity.check(github, s.trustedSigner, trusted = s.trusted()), granted = true))
        rule.onNodeWithText("Robot runs nothing in the GitHub build, not even the Test", substring = true).assertDoesNotExist()
        rule.scrollToText("Run anyway, I understand the risk")
        rule.onNode(hasText("Run anyway, I understand the risk") and isToggleable()).assertIsOn()
        rule.scrollToText("Robot runs: echo robot-ok; command -v timeout")
        rule.onNodeWithContentDescription("Test Termux").performClick()
        assertEquals(listOf("run test"), log)
    }

    @Test fun termuxUpdatesSaysWhatIsNewerAndChecksOnATap() {
        val old = fdroid.copy(versionName = "0.118.2", versionCode = 1001)
        val s = TermuxSettings().trust(TermuxIdentity.check(old))
        val updates = TermuxUpdateState(checkedAt = may2025, github = GitHubLatest("0.118.3", may2025, "0.119.0-beta.3", may2025, mapOf("0.118.3" to may2025)),
            fdroid = FdroidLatest(1002, "0.118.3", 1022, "0.119.0-beta.3"))
        open(ready.copy(settings = s, check = TermuxIdentity.check(old, s.trustedSigner, trusted = s.trusted()), updates = updates))
        rule.scrollToText("Check for Termux updates")
        rule.onNode(hasText("Check for Termux updates") and isToggleable()).assertIsOn()
        rule.scrollToText("Update available — install it from your F-Droid app")
        rule.scrollToText("Suggests 0.118.3")
        rule.scrollToText("a test version, 0.119.0-beta.3, is out too", substring = true)
        rule.scrollToText("Robot asks only api.github.com and f-droid.org", substring = true)
        rule.onNodeWithContentDescription("Check for Termux updates now").performClick()
        assertEquals(listOf("check"), log)
        rule.onNode(hasText("Check for Termux updates") and isToggleable()).performClick()
        assertFalse(saved.last().updateCheck)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
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
