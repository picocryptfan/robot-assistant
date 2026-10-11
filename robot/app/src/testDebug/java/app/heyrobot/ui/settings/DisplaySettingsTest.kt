package app.heyrobot.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import app.heyrobot.core.LookSettings
import app.heyrobot.core.RobotSettings
import app.heyrobot.sys.LookStore
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.LocalLook
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Display: its two switches, what they write to look.json, and that the theme follows at once. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class DisplaySettingsTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun freshLook() {
        LookStore.file(ctx).delete()
        LookStore.reload(ctx)
    }

    @Test fun theSwitchesShowAndChangeTheLook() {
        val seen = ArrayList<LookSettings>()
        rule.show {
            var look by remember { mutableStateOf(LookSettings()) }
            DisplaySettingsScreen(look, onChange = { look = it; seen += it }, onBack = {})
        }
        val colours = rule.onNodeWithText("Use wallpaper colours")
        val classic = rule.onNodeWithText("Classic look")
        colours.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOn().assert(hasText("Match your wallpaper"))
        classic.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        colours.performClick()
        colours.assertIsOff().assert(hasText("Robot's blue"))
        classic.performClick()
        classic.assertIsOn()
        assertEquals(listOf(LookSettings(wallpaperColors = false), LookSettings(wallpaperColors = false, classic = true)), seen)
    }

    @Test fun turningClassicOnWritesLookJsonAndTheThemeFollows() {
        rule.setContent {
            // The app's own theme, reading LookStore, around the page as in MainActivity.
            RobotTheme(dark = false) {
                Column {
                    Text("look: " + LocalLook.current.name)
                    SettingsNav(SettingsRoutes.DISPLAY, RobotSettings(), Snapshot(), emptyList(), {}, NO_ACTIONS, SnackbarHostState(), onBack = {})
                }
            }
        }
        rule.onNodeWithText("look: STOCK").assertExists()
        rule.onNodeWithText("Classic look").performClick()
        rule.onNodeWithText("look: CLASSIC").assertExists()
        LookStore.awaitWrites()
        assertEquals(LookSettings(classic = true), LookSettings.fromJson(LookStore.file(ctx).readText()))
        // The panel (the voice process) reads it again when it opens.
        assertEquals(LookSettings(classic = true), LookStore.reload(ctx))
        rule.onNodeWithText("Use wallpaper colours").performClick()
        LookStore.awaitWrites()
        assertEquals(LookSettings(wallpaperColors = false, classic = true), LookStore.reload(ctx))
    }

    @Test fun anUnknownRouteGoesBack() {
        var back = 0
        rule.setContent { RobotTheme(dark = false) { SettingsNav("later", RobotSettings(), Snapshot(), emptyList(), {}, NO_ACTIONS, SnackbarHostState(), onBack = { back++ }) } }
        rule.waitForIdle()
        assertEquals(1, back)
    }

    @Test fun theSummaryOnTheSettingsRow() {
        assertEquals("Wallpaper colours", displaySummary(LookSettings()))
        assertEquals("Robot's colours · Classic look", displaySummary(LookSettings(wallpaperColors = false, classic = true)))
        assertEquals("Wallpaper colours · Classic look", displaySummary(LookSettings(classic = true)))
    }

    @Test fun light() {
        rule.show { DisplaySettingsScreen(LookSettings(), {}, {}) }
        rule.shot("display_light")
    }

    @Test fun dark() {
        rule.show(dark = true) { DisplaySettingsScreen(LookSettings(wallpaperColors = false, classic = true), {}, {}) }
        rule.shot("display_dark")
    }

    @Test @Config(fontScale = 2f)
    fun atTwiceTheTextSize() {
        rule.show { DisplaySettingsScreen(LookSettings(), {}, {}) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("display_font200")
    }

    private companion object {
        val NO_ACTIONS = app.heyrobot.ui.AppActions(
            talk = {}, openSetup = {}, openTab = {}, openConversation = {}, askPermissions = {}, openAssistantSettings = {},
            download = {}, cancelDownload = {}, import = {}, verify = {}, deleteModel = {}, deleteConversation = {},
            deleteAllConversations = {}, speakTest = {}, say = {}, voiceDownload = {}, voiceCancel = {}, voiceDelete = {},
            voiceBench = {}, voiceReset = {}, askContacts = {}, askCalendar = {}, openAppSettings = {}, openNote = {},
            addClaudeKey = {}, deleteClaudeKey = {}, openPage = {}, bargeTest = {},
        )
    }
}
