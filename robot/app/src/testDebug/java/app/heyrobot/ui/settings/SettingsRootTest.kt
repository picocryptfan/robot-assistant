package app.heyrobot.ui.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.Approval
import app.heyrobot.core.BrainModel
import app.heyrobot.core.GpuFailure
import app.heyrobot.core.ProblemStage
import app.heyrobot.core.LookSettings
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.ToolName
import app.heyrobot.core.WakePhrase
import app.heyrobot.core.WakeSensitivity
import app.heyrobot.core.WeatherPlace
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.Tab
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.PIXEL_10_PRO
import app.heyrobot.ui.testing.PIXEL_9A
import app.heyrobot.ui.testing.facts
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Settings tab: fifteen rows (0.7 added Memory), each with its state under the title, each opening its page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SettingsRootTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    // A Pixel 10 Pro: Fast is the TPU build (0.4.7 shows where the general build runs on other phones).
    private val snap = Snapshot(facts = facts(), phone = PIXEL_10_PRO)

    private fun open(s: RobotSettings = RobotSettings(), snap: Snapshot = this.snap) {
        rule.show { SettingsRoot(s, snap, log.actions, look = LookSettings(), memory = MemorySettings()) }
    }

    @Test fun eachRowOpensItsPage() {
        open()
        val rows = listOf(
            "Wake word" to "openPage ${Page.WakePhrases}",
            "Voice" to "openPage ${Page.Settings(SettingsRoutes.VOICE)}",
            "Actions" to "openPage ${Page.Settings(SettingsRoutes.ACTIONS)}",
            "Safety & privacy" to "openPage ${Page.Safety}",
            "People, notes and calendar" to "openPage ${Page.Settings(SettingsRoutes.PEOPLE_NOTES_CALENDAR)}",
            "Weather" to "openPage ${Page.Settings(SettingsRoutes.WEATHER)}",
            "Helpers" to "openPage ${Page.Helpers}",
            "Conversation" to "openPage ${Page.ConversationSettings}",
            "Model" to "openTab ${Tab.MODEL}",
            "Memory" to "openPage ${Page.Settings(SettingsRoutes.MEMORY)}",
            "History" to "openPage ${Page.Settings(SettingsRoutes.HISTORY)}",
            "Display" to "openPage ${Page.Settings(SettingsRoutes.DISPLAY)}",
            "Assistant setup" to "openSetup",
            "Advanced" to "openPage ${Page.Settings(SettingsRoutes.ADVANCED)}",
            "About" to "openPage ${Page.Settings(SettingsRoutes.ABOUT)}",
        )
        for ((title, action) in rows) {
            rule.scrollToText(title)
            assertEquals(title, emptyList<String>(), rule.touchTargetProblems())
            rule.onRow(title).performClick()
            assertEquals(title, action, log.last())
        }
        assertEquals(rows.size, log.size)
    }

    @Test fun theSummariesSayTheStateNow() {
        val s = RobotSettings(
            wakePhrases = listOf(WakePhrase(1, "hey robot", WakeSensitivity.MEDIUM, true), WakePhrase(2, "computer", WakeSensitivity.LOW, true),
                WakePhrase(3, "jarvis", WakeSensitivity.LOW, true)),
            speakReplies = SpeakReplies.ALWAYS, weatherHome = WeatherPlace("Montreal", 45.5, -73.6),
            approvals = RobotSettings.DEFAULT_APPROVALS + (ToolName.FLASHLIGHT to Approval.OFF),
        )
        assertEquals("On · “hey robot” and 2 others", wakeSummary(s))
        assertEquals("Off", wakeSummary(s.copy(wakeWord = false)))
        assertEquals("Heart · always speaks", voiceSummary(s))
        assertEquals("5 ask first · 1 off", actionsSummary(s))
        assertEquals("Fast · stays loaded 5 min", modelSummary(s, snap))
        // 0.4.7: on a Pixel 9a Fast is the general build, on the GPU unless it failed there or you turned the GPU off.
        assertEquals("Fast on the GPU · stays loaded 5 min", modelSummary(s, Snapshot(phone = PIXEL_9A)))
        assertEquals("Fast on the CPU · stays loaded 5 min", modelSummary(s.copy(modelGpu = false), Snapshot(phone = PIXEL_9A)))
        assertEquals("Fast on the CPU · always loaded", modelSummary(s.copy(keepModelMinutes = RobotSettings.KEEP_ALWAYS),
            Snapshot(phone = PIXEL_9A, gpuFailure = GpuFailure(BrainModel.GENERAL, 1L, ProblemStage.LOADING, null))))
        assertEquals("Live words on", conversationSummary(s))
        assertEquals("On", memorySummary(MemorySettings()))
        assertEquals("On · also on the lock screen", memorySummary(MemorySettings(onLockScreen = true)))
        assertEquals("Off", memorySummary(MemorySettings(on = false, onLockScreen = true)))
        assertEquals("Claude · Ask first", helpersSummary(s, facts()))
        open(s)
        rule.onNodeWithText("Wake word").assert(hasText("On · “hey robot” and 2 others"))
        rule.onNodeWithText("Weather").assert(hasText("Home: Montreal"))
    }

    @Test fun setupThatNeedsAttentionSaysSo() {
        open(snap = Snapshot(facts = facts(recognizerIsRobot = false)))
        rule.scrollToText("Assistant setup")
        rule.onNodeWithText("Assistant setup").assert(hasText("Needs attention"))
        rule.onNodeWithContentDescription("Problem", useUnmergedTree = true).assertExists()
    }

    @Test fun light() {
        open()
        rule.shot("settings_root")
    }
}
