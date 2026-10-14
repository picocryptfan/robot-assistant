package app.heyrobot.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import app.heyrobot.core.update.FileRef
import app.heyrobot.core.update.FoundRelease
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Updates: what it shows for each state, what its taps do, TalkBack and touch targets, and its screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class UpdatesSettingsTest {
    @get:Rule val rule = createComposeRule()

    private val own = GitHubRepo("picocryptfan", "robot-assistant")
    private val offer = FoundRelease("picocryptfan/robot-assistant", "v0.4.6", "0.4.6", false, "2026-10-11T09:30:00Z",
        "What's new\n\n• Updates from Settings › About › Updates\n• Faster wake word", FileRef(101, "Robot-0.4.6.apk", 97_000_000), FileRef(102, "SHA256SUMS", 250))
    private val base = UpdatesUi(
        installed = "0.4.5", settings = UpdateSettings(), repo = own, defaultRepo = own,
        lastCheck = "Last checked today at 14:05. Robot 0.4.6 is available.", offer = offer, canInstall = false,
    )
    private val upToDate = base.copy(offer = null, lastCheck = "Last checked today at 14:05. You're up to date.", canInstall = true)

    @Test fun upToDateShowsTheVersionAndCheckNow() {
        var checks = 0
        rule.show { UpdatesScreen(upToDate, UpdatesActions(check = { checks++ }), onBack = {}) }
        rule.onNodeWithText("Robot 0.4.5").assertExists()
        rule.onNodeWithText("Check now").assert(hasText("Last checked today at 14:05. You're up to date.")).performClick()
        assertEquals(1, checks)
        rule.onNodeWithText("Download").assertDoesNotExist()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun anUpdateOffersDownloadSkipAndGitHubAndAsksForInstallAccess() {
        val taps = ArrayList<String>()
        rule.show {
            UpdatesScreen(base, UpdatesActions(download = { taps += "download" }, skip = { taps += "skip" }, openGitHub = { taps += "github" },
                allowInstalls = { taps += "allow" }), onBack = {})
        }
        rule.onNodeWithText("Robot 0.4.6").assertExists()
        rule.onNodeWithText("11 October 2026 · 97 MB").assertExists()
        rule.onNodeWithText("Faster wake word", substring = true).assertExists()
        rule.onNodeWithText("Download").performClick()
        rule.onNodeWithText("Skip this version").performClick()
        rule.onNodeWithText("See it on GitHub").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Allow Robot to install updates"))
        rule.onNodeWithText("Open").performClick()
        assertEquals(listOf("download", "skip", "github", "allow"), taps)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun aCheckedDownloadOffersInstall() {
        var installs = 0
        rule.show { UpdatesScreen(base.copy(ready = true, canInstall = true), UpdatesActions(install = { installs++ }), onBack = {}) }
        rule.onNodeWithText("Downloaded and checked.").assertExists()
        rule.onNodeWithText("Install").performClick()
        assertEquals(1, installs)
        rule.onNodeWithText("Download").assertDoesNotExist()
        rule.onNodeWithText("Allow Robot to install updates").assertDoesNotExist()
    }

    @Test fun aDownloadShowsItsProgressAndStops() {
        var stops = 0
        rule.show { UpdatesScreen(base.copy(work = Updater.Work.Downloading(48_500_000, 97_000_000)), UpdatesActions(stop = { stops++ }), onBack = {}) }
        rule.onNodeWithText("Downloading… 50 % (49 MB of 97 MB)").assertExists()
        rule.onNodeWithText("Stop").performClick()
        assertEquals(1, stops)
        rule.onNodeWithText("Check now").assertIsNotEnabled()
    }

    @Test fun aSkippedVersionAndARefusedFileSaySo() {
        var unskips = 0
        rule.show {
            UpdatesScreen(base.copy(settings = UpdateSettings(skipped = "0.4.6"), refused = "The downloaded file doesn't match its SHA-256 in SHA256SUMS, so Robot deleted it and won't install it."),
                UpdatesActions(unskip = { unskips++ }), onBack = {})
        }
        rule.onNodeWithText("You skipped this version", substring = true).assertExists()
        rule.onNodeWithText("Robot refused this release's file before", substring = true).assertExists()
        rule.onNodeWithText("Don't skip").performClick()
        assertEquals(1, unskips)
    }

    @Test fun theSwitchesAndTheirDefaults() {
        val seen = ArrayList<UpdateSettings>()
        rule.show {
            var s by remember { mutableStateOf(UpdateSettings()) }
            UpdatesScreen(upToDate.copy(settings = s), UpdatesActions(change = { s = it; seen += it }), onBack = {})
        }
        val scroll = rule.onNode(hasScrollAction())
        fun switch(title: String) = scroll.performScrollToNode(hasText(title)).let { rule.onNodeWithText(title) }
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        switch("Check for updates automatically").assertIsOn()
        switch("Download updates on Wi-Fi").assertIsOn().assertIsEnabled()
        switch("Include test builds").assertIsOff()
        switch("Install updates without asking").assertIsOff()
        switch("Include test builds").performClick()
        switch("Install updates without asking").performClick()
        switch("Check for updates automatically").performClick()
        // Without automatic checks there is nothing to download on Wi-Fi.
        switch("Download updates on Wi-Fi").assertIsNotEnabled()
        assertEquals(listOf(UpdateSettings(testBuilds = true), UpdateSettings(testBuilds = true, installWithoutAsking = true),
            UpdateSettings(autoCheck = false, testBuilds = true, installWithoutAsking = true)), seen)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun whenAnotherAppStoreKeepsRobotsUpdatesAndroidAsksAnyway() {
        rule.show { UpdatesScreen(upToDate.copy(otherOwner = "org.example.store", settings = UpdateSettings(installWithoutAsking = true)), UpdatesActions(), onBack = {}) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Install updates without asking"))
        rule.onNodeWithText("Install updates without asking").assertIsOff().assertIsNotEnabled().assert(hasText("Android asks anyway", substring = true))
    }

    // Robolectric never settles at 411 dp once a text field in a Compose dialog has the focus (PreferencesTest says so): the
    // two tests that type run on its default 320 dp screen.
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun aFork() {
        val seen = ArrayList<UpdateSettings>()
        rule.show {
            var s by remember { mutableStateOf(UpdateSettings()) }
            UpdatesScreen(upToDate.copy(settings = s, repo = s.repoOr(own)), UpdatesActions(change = { s = it; seen += it }), onBack = {})
        }
        val scroll = rule.onNode(hasScrollAction())
        scroll.performScrollToNode(hasText("Repository"))
        rule.onNodeWithText("Use Robot's own repository").assertDoesNotExist()
        rule.onNodeWithText("Repository").assert(hasText("picocryptfan/robot-assistant")).performClick()
        val field = rule.onNode(hasText("owner/name") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        field.performTextReplacement("x")
        rule.onNodeWithText("Not a GitHub repository (owner/name)").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        // A link is fine: it is saved as owner/name (and a version skipped in another repository is forgotten).
        field.performTextReplacement("https://github.com/fork/robot")
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf(UpdateSettings(repo = "fork/robot")), seen)
        scroll.performScrollToNode(hasText("Use Robot's own repository"))
        rule.onNodeWithText("Repository").assert(hasText("fork/robot"))
    }

    @Test fun aForkHasAWayBackToRobotsOwn() {
        val seen = ArrayList<UpdateSettings>()
        val fork = UpdateSettings(repo = "fork/robot", skipped = "0.4.9")
        rule.show { UpdatesScreen(upToDate.copy(settings = fork, repo = GitHubRepo("fork", "robot")), UpdatesActions(change = { seen += it }), onBack = {}) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Use Robot's own repository"))
        rule.onNodeWithText("Repository").assert(hasText("fork/robot"))
        assertEquals(emptyList<UpdateSettings>(), seen)
        rule.onNodeWithText("Use Robot's own repository").assert(hasText("picocryptfan/robot-assistant")).performClick()
        assertEquals(listOf(UpdateSettings()), seen)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theAccessToken() {
        var saved: String? = null
        rule.show { UpdatesScreen(upToDate, UpdatesActions(saveToken = { saved = it }), onBack = {}) }
        val scroll = rule.onNode(hasScrollAction())
        // Save only for something shaped like a token.
        scroll.performScrollToNode(hasText("Access token"))
        rule.onNodeWithText("Access token").assert(hasText("None. Only needed while the repository is private.")).performClick()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        val field = rule.onNode(hasText("Token") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        field.performTextReplacement("not a token")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        scroll.performScrollToNode(hasText("Access token"))
        rule.onNodeWithText("Access token").performClick()
        val token = "github_pat_" + "A1b2C3d4".repeat(10)
        field.performTextReplacement(token)
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(token, saved)
    }

    @Test fun aSavedTokenIsMaskedAndCanBeRemoved() {
        var removed = 0
        rule.show { UpdatesScreen(upToDate.copy(token = "github_pat_…WXYZ"), UpdatesActions(removeToken = { removed++ }), onBack = {}) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Access token"))
        rule.onNodeWithText("github_pat_…WXYZ").assertExists()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Remove the access token"))
        rule.onNodeWithText("Remove the access token").performClick()
        rule.onNodeWithText("Remove the access token?").assertExists()
        rule.onNodeWithText("Remove").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(1, removed)
    }

    @Test fun whatIsSentIsSaid() {
        rule.show { UpdatesScreen(upToDate, UpdatesActions(), onBack = {}) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("What is sent", substring = true))
        rule.onNodeWithText("your IP address and Robot's version in the request; nothing else is sent", substring = true).assertExists()
    }

    @Test fun aboutHasTheUpdatesRowWithItsState() {
        // Settings › About in the redesigned Settings: Version, then Updates with when Robot last checked, then Licences.
        val log = ActionLog()
        rule.show { AboutSettingsScreen(log.actions, onBack = {}) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Checks once a day")).fetchSemanticsNodes().isNotEmpty() }
        rule.onRow("Updates").assert(hasText("Checks once a day")).performClick()
        assertEquals(listOf("openPage ${Page.Settings(SettingsRoutes.UPDATES)}"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /** 0.7: only an update error offers "Report this", which opens Send feedback with the updater's codes. */
    @Test fun upToDateHasNoReportThis() {
        var reports = 0
        rule.show { UpdatesScreen(upToDate, UpdatesActions(report = { reports++ }), onBack = {}) }
        rule.onNodeWithText("Report this").assertDoesNotExist()
        assertEquals(0, reports)
    }

    @Test fun aFailedCheckOffersReportThis() {
        var reports = 0
        rule.show {
            UpdatesScreen(upToDate.copy(lastCheck = "Last checked today at 14:05. GitHub didn't find the repository.", failed = true),
                UpdatesActions(report = { reports++ }), onBack = {})
        }
        rule.onRow("Report this").assert(hasText("Send the developer a bug report with the updater's codes", substring = true)).performClick()
        assertEquals(1, reports)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun light() {
        rule.show { UpdatesScreen(base, UpdatesActions(), {}) }
        rule.shot("updates_light")
    }

    @Test fun dark() {
        rule.show(dark = true) {
            UpdatesScreen(base.copy(offer = offer.copy(tag = "v0.6.0-rc1", version = "0.6.0-rc1", testBuild = true), ready = true, canInstall = true,
                settings = UpdateSettings(testBuilds = true)), UpdatesActions(), {})
        }
        rule.shot("updates_dark")
    }

    /** At twice the text size the page is long: on a screen tall enough for all of it, every target is whole and big enough. */
    @Test @Config(fontScale = 2f, qualifiers = "w411dp-h4200dp-mdpi")
    fun atTwiceTheTextSize() {
        rule.show { UpdatesScreen(base.copy(token = "github_pat_…WXYZ"), UpdatesActions(), {}) }
        rule.onNodeWithText("Skip this version").assertExists()
        rule.onNodeWithText("Remove the access token").assertExists()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("updates_font200")
    }
}
