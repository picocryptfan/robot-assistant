// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.heyrobot.core.StrictJson
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnCheck
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnOp
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.HaPick
import app.heyrobot.core.connect.HaPickers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Add an operation window, driven with what a demo Home Assistant really answers (resources/ha-demo, cut down from its
 * `/api/states` and `/api/services`) through the real parser: a service is picked, its entities show, Save saves.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HaOpPickerTest {
    @get:Rule val rule = createComposeRule()

    private val ha = Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "Home Assistant", ConnUrl.parse("https://home.example.org")!!, auth = ConnAuth.Bearer)
    private fun json(name: String): Any? {
        val text = javaClass.classLoader!!.getResource("ha-demo/$name.json")!!.readText()
        return StrictJson.parse(text)!!.value
    }
    private val pickers = HaPickers(HaPick.states(json("states"))!!, HaPick.services(json("services"))!!)
    private var saved: ConnOp? = null

    private fun open() = rule.setContent {
        Column(Modifier.verticalScroll(rememberScrollState())) { HaOpPicker(ha, pickers, onCancel = {}) { saved = it } }
    }
    private fun text(s: String) = rule.onNodeWithText(s, useUnmergedTree = true)

    @Test fun theRealAnswersReadAsLists() {
        assertEquals(6, pickers.targets("light", "turn_off").size)
        assertNotNull(pickers.service("light", "turn_off"))
    }

    /** The row in the service list (the search field holds the same words once they're typed). */
    private fun pick(service: String) {
        rule.onNodeWithText("Search").performTextInput(service)
        rule.onNode(hasText(service) and hasClickAction() and !hasSetTextAction()).performClick()
        rule.waitForIdle()
    }

    @Test fun aServicesEntitiesShowAndTheOperationSaves() {
        open()
        pick("light.turn_off")
        // Picked: the search is clear for the entities, and they all show.
        text("Entities a step may pick from").assertExists()
        for (e in pickers.targets("light", "turn_off")) text(e.id).performScrollTo()
        text("light.bed_light").performScrollTo().performClick()
        rule.onNodeWithText("Title").performScrollTo().performTextInput("Bed light off")
        rule.waitForIdle()
        rule.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()
        val op = saved!!
        assertEquals(listOf("light.bed_light"), op.params.getValue("entity").options)
        assertTrue(ConnCheck.problems(ha.copy(ops = listOf(op))).toString(), ConnCheck.valid(ha.copy(ops = listOf(op))))
    }

    @Test fun pickingAServicePutsTheKeyboardAwaySoTheEntitiesShow() {
        open()
        pick("light.turn_off")
        rule.onNodeWithText("Search").assertIsNotFocused()
    }

    @Test fun saveSaysWhatItStillNeeds() {
        open()
        pick("light.turn_off")
        rule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        text("To save, pick at least one entity above.").assertExists()
        text("light.bed_light").performScrollTo().performClick()
        rule.waitForIdle()
        text("To save, pick at least one entity above.").assertDoesNotExist()
        text("To save, give it a title in plain text.").assertExists()
        rule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Title").performScrollTo().performTextInput("Bed light off")
        rule.waitForIdle()
        text("To save, give it a title in plain text.").assertDoesNotExist()
        rule.onNodeWithText("Save").performScrollTo().assertIsEnabled()
    }

    @Test fun readingAStateSaysItNeedsOneEntity() {
        open()
        rule.onNodeWithText("Read a state").performClick()
        rule.waitForIdle()
        text("To save, pick an entity above.").assertExists()
    }

    @Test fun aSearchThatFindsNothingSaysSo() {
        open()
        rule.onNodeWithText("Search").performTextInput("no.such.service")
        rule.waitForIdle()
        text("Nothing matches that search. Clear it to see the whole list.").assertExists()
        rule.onNodeWithText("Search").performTextReplacement("")
        pick("light.turn_off")
        // The search now looks through the entities, so a service's name finds none: the list says why it's empty.
        rule.onNodeWithText("Search").performTextInput("light.turn_off")
        rule.waitForIdle()
        text("light.bed_light").assertDoesNotExist()
        text("Nothing matches that search. Clear it to see the whole list.").assertExists()
    }

    @Test fun aServiceWithNothingToActOnSaysSoAndAnotherCanBePicked() {
        open()
        pick("weather.get_forecasts")
        text("Your Home Assistant has nothing this service can act on.").assertExists()
        text("To save, pick at least one entity above.").assertDoesNotExist()
        rule.onNodeWithText("Pick another service").performScrollTo().performClick()
        rule.waitForIdle()
        pick("light.turn_off")
        text("light.bed_light").assertExists()
    }
}
