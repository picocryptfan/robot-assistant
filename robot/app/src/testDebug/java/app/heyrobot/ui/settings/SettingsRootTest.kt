package app.heyrobot.ui.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.Approval
import app.heyrobot.core.LookSettings
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.ToolName
import app.heyrobot.core.WakePhrase
import app.heyrobot.core.WakeSensitivity
import app.heyrobot.core.WeatherPlace
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.Tab
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
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

/** The Settings tab: fourteen rows, each with its state under the title, each opening its page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SettingsRootTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val snap = Snapshot(facts = facts())

    private fun open(s: RobotSettings = RobotSettings(), snap: Snapshot = this.snap) {
        rule.show { SettingsRoot(s, snap, log.actions, look = LookSettings()) }
    }

    @Test fun eachRowOpensItsPage() {
        open()
        val rows = listOf(
            "Wake word" to "openPage ${Page.WakePhrases}",
            "Voice" to "openPage ${Page.Settings(SettingsRoutes.VOICE)}",
            "Actions" to "openPage ${Page.Settings(SettingsRoutes.ACTIONS)}",
            "Safety & privacy" to "openPage ${Page.Safety}",
            "People, notes and calendar" to "openPage ${Page.Settings(SettingsRoutes.PEOPLE_NOTES_CALENDAR)}",
            "Messages" to "openPage ${Page.Settings(SettingsRoutes.MESSAGES)}",
            "Weather" to "openPage ${Page.Settings(SettingsRoutes.WEATHER)}",
            "Helpers" to "openPage ${Page.Helpers}",
            "Conversation" to "openPage ${Page.ConversationSettings}",
            "Model" to "openTab ${Tab.MODEL}",
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
        assertEquals("Fast · stays loaded 5 min", modelSummary(s))
        assertEquals("Live words on", conversationSummary(s))
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
