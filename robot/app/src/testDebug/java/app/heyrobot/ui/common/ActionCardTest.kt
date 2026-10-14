package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ToolName
import app.heyrobot.ui.classic.ClassicActionCard
import app.heyrobot.ui.testing.PHONE
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

/**
 * The panel's action card (ui/classic ClassicActionCard): a card waiting for a choice lists the people or numbers as
 * 48 dp buttons, Cancel and "Do it" say no and yes, a finished card says how it went in words under its check, the extra
 * buttons do what they say, History's cards have no buttons, and everything stays usable at twice the text size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ActionCardTest {
    @get:Rule val rule = createComposeRule()

    private val log = ArrayList<String>()
    private val more = CardActions(
        choose = { id, i, keep -> log += "choose $id $i $keep" }, undo = { log += "undo $it" }, allow = { log += "allow" }, openApp = { log += "open $it" },
    )
    private val confirm: (String, Boolean) -> Unit = { id, yes -> log += "confirm $id $yes" }

    private val text = ActionView("a1", ToolName.SEND_TEXT, "Text **Mom** in Cheogram", ActionStatus.PENDING, null, body = "I'm running late, see you at 7.")
    private val pick = text.copy(id = "a2", choices = listOf("Mom · mobile", "Mom · work"), more = 3, remember = "Always use this for “mom”")
    private val done = ActionView("a3", ToolName.SET_TIMER, "Timer for 10 minutes", ActionStatus.DONE, null)
    private val note = ActionView("a4", ToolName.ADD_NOTE, "Note: buy coffee filters", ActionStatus.DONE, "Saved", undo = true)
    private val noContacts = ActionView("a5", ToolName.SEND_TEXT, "Text **Alex**", ActionStatus.NEEDS_PERMISSION, "Contacts access isn't allowed")
    /** Conversation mode's and a helper's card: "Include the conversation so far?" with its own button words. */
    private val include = ActionView("a6", ToolName.ASK_HELPER, "Include the conversation so far?", ActionStatus.PENDING,
        "Start fresh: Claude sees only what you ask from now on.", confirm = "Include", cancel = "Start fresh")

    /** [all]: the card with choices too (the screenshot leaves it to the panel's, for room). */
    @Composable
    private fun Cards(onConfirm: ((String, Boolean) -> Unit)? = confirm, actions: CardActions? = more, all: Boolean = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (a in listOfNotNull(text, pick.takeIf { all }, done, note, noContacts, include)) ClassicActionCard(a, onConfirm, actions)
        }
    }

    @Test fun choicesAreButtonsAndTheButtonsAnswer() {
        rule.show(classic = true) { Column(Modifier.padding(16.dp)) { ClassicActionCard(text, confirm, more); ClassicActionCard(pick, confirm, more) } }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        for (c in listOf("Mom · mobile", "Mom · work")) {
            rule.onNodeWithText(c).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithText("…and 3 more. Say the full name to pick one of those.").assertExists()
        // "Always use this for “mom”" is ticked before a choice is tapped.
        val keep = rule.onNodeWithText("Always use this for “mom”")
        keep.assertIsOff().assertHeightIsAtLeast(48.dp)
        keep.performClick()
        keep.assertIsOn()
        rule.onNodeWithText("Mom · work").performClick()
        rule.onNodeWithText("Do it").performClick()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Cancel"))[0].performClick()
        assertEquals(listOf("choose a2 1 true", "confirm a1 true", "confirm a1 false"), log)
        // Both buttons are at least 48 dp tall to the finger.
        for (b in listOf("Do it", "Cancel")) {
            val r = rule.onAllNodes(androidx.compose.ui.test.hasText(b))[0].fetchSemanticsNode().touchBoundsInRoot
            assertEquals(b, true, with(rule.density) { r.height.toDp() } >= 48.dp)
        }
    }

    @Test fun aDoneCardSaysSoInWords() {
        rule.show(classic = true) { ClassicActionCard(done, confirm, more) }
        rule.onNodeWithContentDescription("Done").assertExists()
        rule.onNodeWithText("Done").assertExists()
        rule.onNodeWithText("Do it").assertDoesNotExist()
    }

    @Test fun theOtherButtonsDoWhatTheySay() {
        rule.show(classic = true) { Column { ClassicActionCard(note, confirm, more); ClassicActionCard(noContacts, confirm, more); ClassicActionCard(include, confirm, more) } }
        rule.onNodeWithText("Undo").performClick()
        rule.onNodeWithText("Allow").performClick()
        rule.onNodeWithText("Include").performClick()
        rule.onNodeWithText("Start fresh").performClick()
        assertEquals(listOf("undo a4", "allow", "confirm a6 true", "confirm a6 false"), log)
        rule.onNodeWithText("Needs your permission. Contacts access isn't allowed").assertExists()
    }

    @Test fun historysCardsHaveNoButtons() {
        rule.show(classic = true) { Cards(onConfirm = null, actions = null) }
        for (b in listOf("Do it", "Cancel", "Mom · mobile", "Undo", "Allow", "Include")) rule.onNodeWithText(b).assertDoesNotExist()
    }

    @Test fun light() {
        rule.show(classic = true) { Cards(all = false) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("action_cards_light")
    }

    @Test @Config(fontScale = 2f)
    fun atTwiceTheTextSize() {
        rule.show(classic = true) { Column(Modifier.padding(16.dp)) { ClassicActionCard(pick, confirm, more); ClassicActionCard(text, confirm, more) } }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("action_cards_font200")
    }
}
