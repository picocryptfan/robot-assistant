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
import app.heyrobot.core.CalendarInfo
import app.heyrobot.core.CalendarPick
import app.heyrobot.core.EmailApp
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.TextApp
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.Tab
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.facts
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.showSettings
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › People, notes and calendar: radio dialogs for the apps, the calendar and the length, edit dialogs for the gateway and region. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class PeopleNotesCalendarTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val work = CalendarInfo(7, "Work", "me@example.org", owner = "me@example.org", primary = true)

    private fun open(start: RobotSettings = RobotSettings(), calendarAllowed: Boolean = true, calendars: List<CalendarInfo> = listOf(work)): SettingsHost {
        val host = SettingsHost(start)
        val snap = Snapshot(facts = facts().copy(calendarGranted = calendarAllowed, cheogramInstalled = true))
        rule.showSettings(host) { s, u -> PeopleNotesCalendarScreen(s, snap, calendars, u, log.actions, SnackbarHostState(), onBack = {}) }
        return host
    }

    private fun pick(row: String, choice: String) {
        rule.scrollToText(row)
        rule.onRow(row).performClick()
        rule.onNode(isDialog()).assertExists()
        assertEquals(row, emptyList<String>(), rule.touchTargetProblems())
        rule.inDialog(choice).performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun theChoicesWriteTheirSettings() {
        val host = open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Texting app").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, TextApp.CHEOGRAM.title))
        pick("Texting app", TextApp.SMS.title)
        assertEquals(RobotSettings(textApp = TextApp.SMS), host.last)
        pick("Email app", EmailApp.ASK.title)
        assertEquals(EmailApp.ASK, host.last.emailApp)
        pick("Calendar for new events", "Work")
        assertEquals(7L, host.last.eventCalendar)
        pick("Calendar for new events", "Automatic")
        assertEquals(CalendarPick.AUTO, host.last.eventCalendar)
        pick("Length of a new event", "30 min")
        assertEquals(30, host.last.eventMinutes)
        assertEquals(5, host.saved.size)
    }

    @Test fun theCalendarAsksFirst() {
        open(calendarAllowed = false)
        rule.scrollToText("Allow the calendar")
        rule.onRow("Allow").performClick()
        assertEquals(listOf("askCalendar"), log)
    }

    @Test fun theLinks() {
        open()
        for (t in listOf("People", "Robot Notes", "Contacts and calendar access")) {
            rule.scrollToText(t)
            rule.onRow(t).performClick()
        }
        assertEquals(listOf("openPage ${Page.People}", "openTab ${Tab.NOTES}", "openPage ${Page.Setup(people = true)}"), log)
    }

    // Robolectric settles with a focused text field in a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun aWrongRegionCantBeSaved() {
        val host = open()
        rule.scrollToText("Phone region")
        rule.onRow("Phone region").assert(hasText("This phone's own", substring = true)).performClick()
        val field = rule.onNode(hasText("Region") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        field.performTextReplacement("USA")
        rule.inDialog("Save").assertIsNotEnabled()
        rule.inDialog("Two letters, such as US or FR").assertExists()
        field.performTextReplacement("fr")
        rule.inDialog("Save").assertIsEnabled().performClick()
        assertEquals("FR", host.last.phoneRegion)
    }
}
