package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.heyrobot.core.BriefItem
import app.heyrobot.core.BriefLock
import app.heyrobot.core.BriefReminder
import app.heyrobot.core.BriefRoutine
import app.heyrobot.core.OnlineUse
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ReadyCard
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineFile
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.RoutineWords
import app.heyrobot.core.RunRecord
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.TriggerKind
import app.heyrobot.sys.RoutineFacts
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.facts
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Settings › Routines (0.8): the brief and your routines with their switches and last runs, Ready cards, when routines may
 * run, what they need; the brief's page; a routine's page, which edits it and shows its card before Save.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class RoutinesSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val calls = ArrayList<String>()
    private val now = ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, ZoneId.of("America/Toronto"))
    private var saved: Routine? = null
    private var lastSettings: RoutineSettings? = null

    private fun tool(name: String, vararg args: Pair<String, Any?>) = RoutineStep.Tool((ToolCalls.parse(RawToolCall(name, args.toMap())) as ParsedCall.Ok).action)

    private val leaving = Routine("r-1", "Leaving work", RoutineTrigger.At(LocalTime.of(17, 0), RoutineWords.WEEKDAYS),
        listOf(tool("weather"), tool("maps_navigate", "destination" to "home")), created = 1, online = setOf(OnlineUse.OPEN_METEO))
    private val night = Routine("r-2", "Night", RoutineTrigger.Charging(), listOf(tool("set_alarm", "hour" to 6, "minute" to 30)), enabled = false, created = 1)
    private val ready = ReadyCard("r-1", "Leaving work", leaving.steps[1], 5L, now.toLocalDate())
    private val allFacts = RoutineFacts(exactAlarms = true, notifications = true, clock = true, calendar = false, location = false)

    private fun data(rs: RoutineSettings = RoutineSettings(), problem: RoutineFile? = null) = RoutinesData(
        routines = listOf(leaving, night), settings = rs,
        runs = mapOf("r-1" to RunRecord(now.minusHours(16).toInstant().toEpochMilli(), TriggerKind.TIME, 2, 1, 0)),
        ready = listOf(ready), reminders = listOf(BriefReminder("bring the passport", LocalDate.of(2026, 10, 13))), problem = problem, facts = allFacts,
    )

    private fun actions() = RoutineActions(
        open = { calls += "open $it" }, save = { saved = it; calls += "save ${it.name}"; true }, delete = { calls += "delete $it" },
        setEnabled = { id, on -> calls += "enabled $id $on" }, settings = { lastSettings = it; calls += "settings" }, runNow = { calls += "run $it" },
        openReady = { calls += "ready $it" }, removeReminder = { calls += "remove ${it.text}" }, startOver = { calls += "startOver" },
    )

    @Test fun theRoutinesPageListsTheBriefYourRoutinesAndWhatWaits() {
        rule.show { RoutinesScreen(data(), RobotSettings(), facts(), actions(), log.actions, SnackbarHostState(), onBack = {}, now = now) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Every weekday at 17:00 · 2 steps\nLast run: yesterday 17:00 · a time · 2 steps, 1 ready").assertExists()
        rule.shot("routines_light")
        // The brief's switch: its card first (Open-Meteo listed), then on.
        rule.onNodeWithContentDescription("Morning brief on").performClick()
        rule.inDialog("Turn on the morning brief?").assertExists()
        rule.inDialog("Open-Meteo", substring = true).assertExists()
        rule.inDialog("Turn on").performClick()
        assertEquals(RoutineSettings(briefEnabled = true, briefOnline = true), lastSettings)
        rule.onNodeWithContentDescription("Night on").performClick()
        assertEquals("enabled r-2 true", calls.last())
        rule.onRow("Leaving work").performClick()
        assertEquals("open routines/r-1", calls.last())
        rule.onRow("Morning brief").performClick()
        assertEquals("open ${RoutineRoutes.BRIEF}", calls.last())
        rule.scrollToText("Ready (1)")
        rule.onRow("Your “Leaving work” routine is ready to: Search Organic Maps for “home”, then tap Route.").performClick()
        assertEquals("ready ${ready.key}", calls.last())
        rule.scrollToText("Add a routine")
        rule.onRow("Add a routine").performClick()
        assertEquals("open ${RoutineRoutes.NEW}", calls.last())
        rule.scrollToText("Routines may go online by themselves")
        rule.onRow("Routines may go online by themselves").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState,
            androidx.compose.ui.state.ToggleableState.On)).performClick()
        assertEquals(RoutineSettings(online = false), lastSettings)
        // What routines need: the calendar and the weather's place aren't allowed here; Allow asks in the app.
        rule.scrollToText("Calendar")
        rule.onAllNodes(hasText("Allow") and androidx.compose.ui.test.hasClickAction()).assertCountEquals(2)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun warningsSayWhyRoutinesWontRun() {
        rule.show { RoutinesScreen(data(RoutineSettings(runLocked = false), RoutineFile.Damaged), RobotSettings(), facts(active = false), actions(), log.actions,
            SnackbarHostState(), onBack = {}, now = now) }
        rule.onNodeWithText("Routines need Robot as the digital assistant.", substring = true).assertExists()
        rule.onNodeWithText("Robot can't read your routines file", substring = true).assertExists()
        rule.onNodeWithText("Start over").performClick()
        rule.inDialog("Start over").performClick()
        assertEquals("startOver", calls.last())
        // Paused: the routines' switches and Add are off until then.
        rule.onNodeWithContentDescription("Leaving work on").assertIsNotEnabled()
        rule.onRow("Not running").performClick()
        assertEquals("openSetup", log.last())
    }

    @Test fun theBriefsPageLooksLikeSettings() {
        rule.show { BriefSettingsScreen(data(RoutineSettings(briefEnabled = true, briefOnline = true)), RoutineActions(), SnackbarHostState(), onBack = {}) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("routines_brief")
    }

    // Robolectric settles with a focused text field in a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theBriefsPageChangesItsSettings() {
        var rs by mutableStateOf(RoutineSettings(briefEnabled = true, briefOnline = true))
        val act = RoutineActions(settings = { rs = it; lastSettings = it }, runNow = { calls += "run $it" }, removeReminder = { calls += "remove ${it.text}" })
        rule.show { BriefSettingsScreen(data(rs), act, SnackbarHostState(), onBack = {}) }
        rule.onRow("Runs").performClick()
        rule.inDialog("At a time").performClick()
        assertEquals(RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.WEEKDAYS), rs.briefTrigger)
        rule.scrollToText("Time")
        rule.onRow("Time").performClick()
        rule.onNode(hasText("Time") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("6:45")
        rule.inDialog("Save").performClick()
        assertEquals(LocalTime.of(6, 45), (rs.briefTrigger as RoutineTrigger.At).time)
        rule.scrollToText("Weather")
        rule.onRow("Weather").performClick()
        assertTrue(BriefItem.WEATHER !in rs.briefItems)
        rule.scrollToText("Unread messages")
        rule.onRow("Unread messages").assertIsNotEnabled()
        rule.scrollToText("On the lock screen")
        rule.onRow("On the lock screen").performClick()
        rule.inDialog("Nothing until I unlock").performClick()
        assertEquals(BriefLock.NOTHING, rs.briefLock)
        rule.scrollToText("Hear it now")
        rule.onRow("Hear it now").performClick()
        assertEquals("run ${BriefRoutine.ID}", calls.last())
        rule.scrollToText("bring the passport")
        rule.onNodeWithContentDescription("Remove “bring the passport”").performClick()
        assertEquals("remove bring the passport", calls.last())
        // Turning it off needs no card; back on after you agreed once, none either.
        rule.scrollToText("Use the morning brief")
        rule.onRow("Use the morning brief").performClick()
        assertEquals(false, rs.briefEnabled)
    }

    @Test fun aRoutinesPageLooksLikeSettings() {
        rule.show { RoutineEditScreen(leaving, false, data(), actions(), SnackbarHostState(), onBack = {}, now = now) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("routine_edit")
        rule.scrollToText("Delete")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun aNewRoutineIsBuiltFromTheListAndSavedThroughItsCard() {
        var back = 0
        rule.show { RoutineEditScreen(Routine("r-9", "", RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.WEEKDAYS), emptyList(), created = 0), true,
            data(), actions(), SnackbarHostState(), onBack = { back++ }, now = now) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        // Nothing to save yet: it says what is missing.
        rule.onNodeWithText("Save").performClick()
        rule.inDialog("Give the routine a name.", substring = true).assertExists()
        rule.inDialog("OK").performClick()
        rule.onRow("Name").performClick()
        rule.onNode(hasText("Name") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("Coffee")
        rule.inDialog("Save").performClick()
        rule.scrollToText("Add a step")
        rule.onRow("Add a step").performClick()
        rule.inDialog("Set a timer").performClick()
        rule.onNode(hasText("Minutes") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("10")
        rule.inDialog("Add").performClick()
        rule.scrollToText("Add a step")
        rule.onRow("Add a step").performClick()
        rule.inDialog("Read your morning brief").performClick()
        rule.scrollToText("2. Read your morning brief")
        rule.onNodeWithContentDescription("Move step 2 up").performClick()
        rule.onNodeWithText("1. Read your morning brief").assertExists()
        rule.onNodeWithText("Save").performClick()
        rule.inDialog("Save the routine “Coffee”?").assertExists()
        rule.inDialog("Online by itself: Open-Meteo (the weather). Nothing about you is sent.", substring = true).assertExists()
        rule.inDialog("Save").performClick()
        val r = saved!!
        assertEquals("Coffee", r.name)
        assertEquals(listOf(RoutineStep.Brief, tool("set_timer", "minutes" to 10)), r.steps)
        assertEquals(setOf(OnlineUse.OPEN_METEO), r.online)
        assertTrue(r.created > 0)
        assertEquals(1, back)
    }

    @Test fun aRoutinesPageRunsItNowOrDeletesIt() {
        var back = 0
        rule.show { RoutineEditScreen(leaving, false, data(), actions(), SnackbarHostState(), onBack = { back++ }, now = now) }
        rule.scrollToText("Run now")
        rule.onRow("Run now").performClick()
        assertEquals("run r-1", calls.last())
        rule.onRow("Delete").performClick()
        rule.inDialog("Delete").performClick()
        assertEquals("delete r-1", calls.last())
        assertEquals(1, back)
        // Its trigger is picked from the list; another kind starts over with that kind's defaults.
        rule.scrollToText("Starts")
        rule.onRow("Starts").performClick()
        rule.inDialog("When charging starts").performClick()
        rule.scrollToText("At most once a day")
        rule.onRow("At most once a day").assertExists()
        rule.onNode(isDialog()).assertDoesNotExist()
    }
}
