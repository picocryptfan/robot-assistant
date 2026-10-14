package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
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
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Display: its one switch (the colours), what it writes to look.json, and that a look.json from before 1.0,
 * with the Classic look switch, still loads. The Classic look switch and its Look group are gone.
 */
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

    @Test fun theSwitchShowsAndChangesTheColours() {
        val seen = ArrayList<LookSettings>()
        rule.show {
            var look by remember { mutableStateOf(LookSettings()) }
            DisplaySettingsScreen(look, onChange = { look = it; seen += it }, onBack = {})
        }
        val colours = rule.onNodeWithText("Use wallpaper colours")
        colours.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOn().assert(hasText("Match your wallpaper"))
        rule.onNodeWithText("Classic look").assertDoesNotExist()
        rule.onNodeWithText("Look").assertDoesNotExist()
        rule.onNodeWithText("Light or dark follows your phone.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        colours.performClick()
        colours.assertIsOff().assert(hasText("Robot's blue"))
        assertEquals(listOf(LookSettings(wallpaperColors = false)), seen)
    }

    @Test fun aChangeWritesLookJson() {
        rule.setContent {
            RobotTheme(dark = false) { SettingsNav(SettingsRoutes.DISPLAY, RobotSettings(), Snapshot(), emptyList(), {}, NO_ACTIONS, SnackbarHostState(), onBack = {}) }
        }
        rule.onNodeWithText("Use wallpaper colours").performClick()
        LookStore.awaitWrites()
        assertEquals(LookSettings(wallpaperColors = false), LookSettings.fromJson(LookStore.file(ctx).readText()))
        // The panel (the voice process) reads it again when it opens.
        assertEquals(LookSettings(wallpaperColors = false), LookStore.reload(ctx))
    }

    @Test fun aLookJsonFromBefore10StillLoads() {
        // 0.9 with the Classic look on and Robot's own colours.
        LookStore.file(ctx).apply { parentFile?.mkdirs() }.writeText("""{"v": 1, "wallpaperColors": false, "classic": true}""")
        assertEquals(LookSettings(wallpaperColors = false), LookStore.reload(ctx))
        rule.setContent {
            RobotTheme(dark = false) { SettingsNav(SettingsRoutes.DISPLAY, RobotSettings(), Snapshot(), emptyList(), {}, NO_ACTIONS, SnackbarHostState(), onBack = {}) }
        }
        rule.onNodeWithText("Use wallpaper colours").assertIsOff()
        // The next save writes the file without the old key.
        rule.onNodeWithText("Use wallpaper colours").performClick()
        LookStore.awaitWrites()
        assertEquals(false, LookStore.file(ctx).readText().contains("classic"))
        assertEquals(LookSettings(), LookStore.reload(ctx))
    }

    @Test fun anUnknownRouteGoesBack() {
        var back = 0
        rule.setContent { RobotTheme(dark = false) { SettingsNav("later", RobotSettings(), Snapshot(), emptyList(), {}, NO_ACTIONS, SnackbarHostState(), onBack = { back++ }) } }
        rule.waitForIdle()
        assertEquals(1, back)
    }

    @Test fun theSummaryOnTheSettingsRow() {
        assertEquals("Wallpaper colours", displaySummary(LookSettings()))
        assertEquals("Robot's colours", displaySummary(LookSettings(wallpaperColors = false)))
    }

    @Test fun light() {
        rule.show { DisplaySettingsScreen(LookSettings(), {}, {}) }
        rule.shot("display_light")
    }

    @Test fun dark() {
        rule.show(dark = true) { DisplaySettingsScreen(LookSettings(wallpaperColors = false), {}, {}) }
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
            openPage = {}, bargeTest = {},
        )
    }
}
