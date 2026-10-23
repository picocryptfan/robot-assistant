// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.heyrobot.core.skills.NameContext
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillFile
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.core.skills.SkillMade
import app.heyrobot.core.skills.SkillParam
import app.heyrobot.core.skills.SkillProvenance
import app.heyrobot.core.skills.SkillRunRecord
import app.heyrobot.core.skills.SkillStep
import app.heyrobot.core.skills.StepKind
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.time.ZonedDateTime

/** Settings › Skills (plan §9, Test Bench SK8 and SK9): the list, a skill's page, names checked as you type, Delete asks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SkillsSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val calls = ArrayList<String>()
    private val now = ZonedDateTime.of(2026, 10, 19, 18, 0, 0, 0, ZoneId.of("America/Toronto"))

    private val home = Skill("s-k3f9q2", 3, "Heading home", listOf("heading home"), listOf("je rentre"),
        listOf(SkillParam("who", ParamType.PERSON, "Sam", "Who should I tell?", "À qui je le dis?")),
        listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "On my way home.")), SkillStep.of(StepKind.MAPS_NAVIGATE, mapOf("destination" to "home"))),
        SkillLevel.ASK_FIRST, SkillProvenance(SkillMade.VOICE, false, now.minusDays(1).toInstant().toEpochMilli(), now.minusDays(1).toInstant().toEpochMilli()))
    private val pasta = Skill("s-pasta1", 1, "Pasta", listOf("pasta"), emptyList(), emptyList(),
        listOf(SkillStep.of(StepKind.SET_TIMER, mapOf("seconds" to 720L))), SkillLevel.JUST_DO_IT, SkillProvenance(SkillMade.DEMO, false, 1L, 1L))

    private fun data(skills: List<Skill> = listOf(home, pasta), problem: SkillFile? = null) = SkillsData(skills, problem, 0,
        mapOf(home.id to SkillRunRecord(now.withHour(17).withMinute(32).toInstant().toEpochMilli(), 2, 2)),
        NameContext(skills = skills, routines = listOf("Leaving work")))

    private fun actions(onSave: (Skill) -> Unit = {}) = SkillActions(open = { calls += "open $it" }, save = { onSave(it); calls += "save ${it.name}"; true },
        delete = { calls += "delete $it" }, startOver = { calls += "startOver" })

    /** WP9b: the list with the menus in French (strings_skills.xml, core's SkillStrings); your skills' names as they are. */
    @Test @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    fun inFrench() {
        rule.show { SkillsScreen(data(), actions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.onNodeWithText("Tes compétences").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("skills_fr")
    }

    @Test fun theListShowsEachSkillWithItsLevelAndLastRun() {
        rule.show { SkillsScreen(data(), actions(), SnackbarHostState(), onBack = {}, now = now) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Ask first · today 17:32 · 2 of 2 done").assertExists()
        rule.onNodeWithText("Just do it · Never run").assertExists()
        rule.onRow("Heading home").performClick()
        assertEquals("open skills/s-k3f9q2", calls.last())
        rule.onNodeWithText("Teach a skill by voice", substring = true).assertExists()
    }

    /** Phase B: "Let Robot's model pick skills" (skill_settings.json), and a skill's fills by model after 5 samples. */
    @Test fun theModelsSwitchAndTheFills() {
        var picks: Boolean? = null
        rule.show {
            SkillsScreen(data().copy(settings = app.heyrobot.core.skills.SkillSettings(modelPicks = true)),
                SkillActions(setModelPicks = { picks = it }), SnackbarHostState(), onBack = {}, now = now)
        }
        rule.scrollToText("Let Robot's model pick skills")
        rule.onNodeWithText("Up to 3 of your skills are described to Robot's model when your words fit them. Off: only their names run them.").assertExists()
        rule.onRow("Let Robot's model pick skills").performClick()
        assertEquals(false, picks)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun aSkillsPageShowsItsFillsAfterFive() {
        var st = app.heyrobot.core.skills.SkillState()
        repeat(4) { st = app.heyrobot.core.skills.SkillStats.record(st, home, app.heyrobot.core.skills.FillModel.FAST, true) }
        val four = st
        rule.show { SkillPageScreen(home, data().copy(state = four), actions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.scrollToText("Delete skill")
        rule.onNodeWithText("Fills by Robot's model").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun aSkillsPageShowsItsFillsByModel() {
        var st = app.heyrobot.core.skills.SkillState()
        repeat(4) { st = app.heyrobot.core.skills.SkillStats.record(st, home, app.heyrobot.core.skills.FillModel.FAST, true) }
        st = app.heyrobot.core.skills.SkillStats.record(st, home, app.heyrobot.core.skills.FillModel.FAST, false)
        val five = st
        rule.show { SkillPageScreen(home, data().copy(state = five), actions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.scrollToText("Fills by Robot's model")
        rule.onNodeWithText("On Fast: 4 of the last 5 right").assertExists()
    }

    @Test fun aFileThatCantBeReadOffersStartOver() {
        rule.show { SkillsScreen(data(emptyList(), SkillFile.Damaged), actions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.onNodeWithText("Robot couldn't read your skills.").assertExists()
        rule.onNodeWithText("Start over").performClick()
        rule.inDialog("Start over").performClick()
        assertEquals("startOver", calls.last())
    }

    @Test fun aNewerFileIsLeftAlone() {
        rule.show { SkillsScreen(data(emptyList(), SkillFile.TooNew(2)), actions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.onNodeWithText("Your skills are from a newer Robot. Update Robot to use them.").assertExists()
    }

    // Robolectric settles with a focused text field in a dialog only on its default small screen (RoutinesSettingsTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun aSkillsPageRenamesChecksNamesMovesAndDeletes() {
        var s by mutableStateOf(home)
        var back = 0
        rule.show { SkillPageScreen(s, data(listOf(s, pasta)), actions { s = it }, SnackbarHostState(), onBack = { back++ }, now = now) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        // A name Robot already uses: the reason shows and Save stays off (D15).
        rule.onRow("Name").performClick()
        rule.onNode(hasText("Name") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("read my messages")
        rule.inDialog("Robot already uses “read my messages” for your messages. Pick another name.").assertExists()
        rule.inDialog("Save").assertIsNotEnabled()
        rule.onNode(hasText("Name") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("Leaving work")
        rule.inDialog("A routine is called “Leaving work”.").assertExists()
        rule.onNode(hasText("Name") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("Going home")
        rule.inDialog("Save").performClick()
        assertEquals("Going home", s.name)
        assertEquals(4, s.rev)
        // Another name in English, then a step moved down and the other removed.
        rule.scrollToText("Add a name")
        rule.onAllNodes(hasText("Add a name")).onFirst().performClick()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("on my way")
        rule.inDialog("Save").performClick()
        assertEquals(listOf("going home", "on my way"), s.namesEn)
        rule.scrollToText("Steps")
        rule.onNodeWithContentDescription("Move step 1 down").performClick()
        assertEquals(listOf(StepKind.MAPS_NAVIGATE, StepKind.SEND_TEXT), s.steps.map { it.kind })
        rule.onNodeWithContentDescription("Remove step 2").performClick()
        assertEquals(listOf(StepKind.MAPS_NAVIGATE), s.steps.map { it.kind })
        assertTrue(s.params.isEmpty())
        // Delete asks first.
        rule.scrollToText("Delete skill")
        rule.onRow("Delete skill").performClick()
        rule.inDialog("Delete “Going home”?").assertExists()
        rule.inDialog("Delete").performClick()
        assertEquals("delete s-k3f9q2", calls.last())
        assertEquals(1, back)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theLevelAndAQuestion() {
        var s by mutableStateOf(home)
        rule.show { SkillPageScreen(s, data(listOf(s, pasta)), actions { s = it }, SnackbarHostState(), onBack = {}, now = now) }
        rule.onRow("Level").performClick()
        rule.inDialog("Off").performClick()
        assertEquals(SkillLevel.OFF, s.level)
        assertEquals(3, s.rev)
        // From Off, it turns on at Ask first: Just do it isn't offered yet.
        rule.onRow("Level").performClick()
        rule.onAllNodes(hasText("Just do it")).assertCountEquals(0)
        rule.inDialog("Ask first").performClick()
        assertEquals(SkillLevel.ASK_FIRST, s.level)
        rule.scrollToText("Default")
        rule.onRow("Default").performClick()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("Marie")
        rule.inDialog("Save").performClick()
        assertEquals("Marie", s.params.single().default)
        rule.scrollToText("Remove")
        rule.onRow("Remove").performClick()
        assertTrue(s.params.isEmpty())
        assertEquals("Marie", s.steps[0].args["to"])
    }

    @Test fun aSkillMadeFromContentAsksYouToCheckItsSteps() {
        var s by mutableStateOf(home.copy(provenance = home.provenance.copy(fromContent = true)))
        rule.show { SkillPageScreen(s, data(listOf(s)), actions { s = it }, SnackbarHostState(), onBack = {}, now = now) }
        rule.onNodeWithText("Made from the screen or something Robot read. Check its steps.").assertExists()
        rule.onRow("Level").performClick()
        rule.onAllNodes(hasText("Just do it")).assertCountEquals(0)
        rule.inDialog("Cancel").performClick()
        rule.onNodeWithText("These steps are mine").performClick()
        assertFalse(s.provenance.fromContent)
        assertEquals(3, s.rev)
    }
}
