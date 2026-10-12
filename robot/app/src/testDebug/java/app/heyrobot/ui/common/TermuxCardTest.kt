package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
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
 * The Termux command card (0.9, B5.2) in both looks: the command in monospace, Robot's warnings as chips, Gemma's reason
 * labelled as Gemma's, the folder and the time limit, Run / Edit / Cancel and Open in Termux while it waits, Stop while it
 * runs, the exit line and the output after; History's card has no buttons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TermuxCardTest {
    @get:Rule val rule = createComposeRule()

    private val log = ArrayList<String>()
    private val more = CardActions(termux = { id, c -> log += "termux $id $c" })
    private val confirm: (String, Boolean) -> Unit = { id, yes -> log += "confirm $id $yes" }

    private val pull = ActionView("t1", ToolName.TERMUX_COMMAND, "Run a command in Termux", ActionStatus.PENDING, "This command changes, overwrites or moves files: check the card, then tap Run.",
        termux = TermuxCardView("k1", "git -C ~/notes pull --ff-only", chips = listOf("Changes, overwrites or moves files", "Uses the internet"),
            why = "Gets the latest changes of your notes repository.", tapOnly = true))
    private val top = ActionView("t2", ToolName.TERMUX_COMMAND, "Run a command in Termux", ActionStatus.PENDING, null,
        termux = TermuxCardView("k2", "htop", chips = listOf("Needs a terminal: open it in Termux"), terminal = true, tapOnly = true))
    private val script = ActionView("t3", ToolName.TERMUX_SCRIPT, "Run your script “show-args”", ActionStatus.PENDING, null,
        termux = TermuxCardView("k3", "~/robot/show-args one 'two words' 'three;rm'", script = "show-args", readsAs = "3 arguments: “one”, “two words”, “three;rm”",
            where = "~/robot", editable = false))
    private val running = ActionView("t4", ToolName.TERMUX_COMMAND, "Run a command in Termux", ActionStatus.PENDING, null,
        termux = TermuxCardView("k4", "sleep 60", running = true))
    private val done = ActionView("t5", ToolName.TERMUX_COMMAND, "Ran a command in Termux (exit 0)", ActionStatus.DONE, "Done in 0.8 s.",
        termux = TermuxCardView("k5", "du -sh ~", exitLine = "Exit 0 · 0.8 s", output = "412M\t/data/data/com.termux/files/home", size = "40 characters"))
    private val stopped = ActionView("t6", ToolName.TERMUX_COMMAND, "Ran a command in Termux (exit 124)", ActionStatus.FAILED, "Took longer than 60 s and was stopped.",
        termux = TermuxCardView("k6", "sleep 120", exitLine = "Exit 124 · 75 s: took longer than 60 s and was stopped", fromContent = true))

    @Composable
    private fun Cards(classic: Boolean, all: List<ActionView>) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (a in all) if (classic) ClassicActionCard(a, confirm, more) else ActionCard(a, confirm, more)
        }
    }

    @Test fun runEditAndCancelAnswerTheCard() {
        rule.show { Cards(false, listOf(pull)) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Gemma: “Gets the latest changes of your notes repository.”").assertExists()
        rule.onNodeWithContentDescription("Command: git -C ~/notes pull --ff-only").assertExists()
        rule.onNodeWithText("Uses the internet").assertExists()
        rule.onNodeWithText("Run").performClick()
        rule.onNodeWithText("Cancel").performClick()
        // Edit: a text field, read again for warnings as you type; Run then sends the edited command.
        rule.onNodeWithText("Edit").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("git -C ~/notes status")
        rule.onNodeWithText("Uses the internet").assertDoesNotExist()
        rule.onNodeWithText("Run").performClick()
        assertEquals(listOf("confirm t1 true", "confirm t1 false", "termux t1 Editing", "termux t1 Run(command=git -C ~/notes status, limitSeconds=60)"), log)
    }

    @Test fun theLimitOpenInTermuxAndStop() {
        rule.show { Cards(false, listOf(top, running)) }
        rule.onNodeWithText("Open in Termux instead").performClick()
        rule.onNodeWithText("Stop").performClick()
        // Another limit for this run only.
        rule.onNodeWithText("In: ~ · Time limit: 60 s ▾").performClick()
        rule.onNodeWithText("2 min").performClick()
        rule.onNodeWithText("Run").performClick()
        assertEquals(listOf("termux t2 OpenInTermux", "termux t4 Stop", "termux t2 Run(command=null, limitSeconds=120)"), log)
    }

    @Test fun historysCardHasNoButtons() {
        rule.show { Column { ActionCard(pull, null, null); ActionCard(done, null, null) } }
        for (b in listOf("Run", "Edit", "Cancel", "Copy")) rule.onNodeWithText(b).assertDoesNotExist()
        rule.onNodeWithText("Ran a command in Termux (exit 0)").assertExists()
    }

    @Test fun light() {
        rule.show { Cards(false, listOf(pull, script, done, stopped)) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("termux_card_light")
    }

    @Test fun classic() {
        rule.show(classic = true) { Cards(true, listOf(pull, script, done, stopped)) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("termux_card_classic")
    }

    @Test fun dark() {
        rule.show(dark = true) { Cards(false, listOf(top, running, done)) }
        rule.shot("termux_card_dark")
    }
}
