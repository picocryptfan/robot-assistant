// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ToolName
import app.heyrobot.core.TermuxCommandAction
import app.heyrobot.core.TermuxScriptAction
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.termux.CommandWarning
import app.heyrobot.core.termux.TermuxCards
import app.heyrobot.core.termux.TermuxOutcome
import app.heyrobot.core.termux.TermuxOutput
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
 * The Termux command card (0.9, B5.2) in the panel's bubbles: the command in monospace, Robot's warnings as chips, Gemma's reason
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
        termux = TermuxCardView("k1", "git -C ~/notes pull --ff-only", chips = listOf(CommandWarning.OVERWRITES.text, CommandWarning.INTERNET.text),
            why = "Gets the latest changes of your notes repository.", tapOnly = true))
    private val top = ActionView("t2", ToolName.TERMUX_COMMAND, "Run a command in Termux", ActionStatus.PENDING, null,
        termux = TermuxCardView("k2", "htop", chips = listOf(CommandWarning.NEEDS_TERMINAL.text), terminal = true, tapOnly = true))
    private val script = ActionView("t3", ToolName.TERMUX_SCRIPT, "Run your script “show-args”", ActionStatus.PENDING, null,
        termux = TermuxCardView("k3", "~/robot/show-args one 'two words' 'three;rm'", script = "show-args", readsAs = TermuxCards.argumentsText(listOf("one", "two words", "three;rm")),
            where = "~/robot", editable = false))
    private val running = ActionView("t4", ToolName.TERMUX_COMMAND, "Run a command in Termux", ActionStatus.PENDING, null,
        termux = TermuxCardView("k4", "sleep 60", running = true))
    private val done = ActionView("t5", ToolName.TERMUX_COMMAND, "Ran a command in Termux (exit 0)", ActionStatus.DONE, "Done in 0.8 s.",
        termux = TermuxCardView("k5", "du -sh ~", exitLine = TermuxOutput.exitLineText(0, 800), output = "412M\t/data/data/com.termux/files/home",
            size = TermuxCards.sizeText(40)))
    private val stopped = ActionView("t6", ToolName.TERMUX_COMMAND, "Ran a command in Termux (exit 124)", ActionStatus.FAILED, "Took longer than 60 s and was stopped.",
        termux = TermuxCardView("k6", "sleep 120", exitLine = TermuxCards.exitText(TermuxOutcome(124, 75_000, limitSeconds = 60)), fromContent = true))

    @Composable
    private fun Cards(all: List<ActionView>) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (a in all) ClassicActionCard(a, confirm, more)
        }
    }

    @Test fun runEditAndCancelAnswerTheCard() {
        rule.show(classic = true) { Cards(listOf(pull)) }
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
        rule.show(classic = true) { Cards(listOf(top, running)) }
        rule.onNodeWithText("Open in Termux instead").performClick()
        rule.onNodeWithText("Stop").performClick()
        // Another limit for this run only.
        rule.onNodeWithText("In: ~ · Time limit: 60 s ▾").performClick()
        rule.onNodeWithText("2 min").performClick()
        rule.onNodeWithText("Run").performClick()
        assertEquals(listOf("termux t2 OpenInTermux", "termux t4 Stop", "termux t2 Run(command=null, limitSeconds=120)"), log)
    }

    @Test fun historysCardHasNoButtons() {
        rule.show(classic = true) { Column { ClassicActionCard(pull, null, null); ClassicActionCard(done, null, null) } }
        for (b in listOf("Run", "Edit", "Cancel", "Copy")) rule.onNodeWithText(b).assertDoesNotExist()
        rule.onNodeWithText("Ran a command in Termux (exit 0)").assertExists()
    }

    @Test fun classic() {
        rule.show(classic = true) { Cards(listOf(pull, script, done, stopped)) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("termux_card_classic")
    }

    @Test fun dark() {
        rule.show(dark = true, classic = true) { Cards(listOf(top, running, done)) }
        rule.shot("termux_card_dark")
    }

    /** 1.1: French menus (values-fr-rCA and core's TermuxStrings): the card's words, its lines and its buttons in Québec French. */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun inFrench() {
        val fr = Langs.of(Lang.FR)
        val pullFr = pull.copy(summary = TermuxCommandAction("git -C ~/notes pull --ff-only").describeText.render(fr),
            detail = "Cette commande modifie, écrase ou déplace des fichiers\u00A0: vérifie la carte, puis touche Lancer.")
        val scriptFr = script.copy(summary = TermuxScriptAction("show-args").describeText.render(fr))
        val doneFr = done.copy(summary = TermuxCards.savedText(null, 0).render(fr), detail = TermuxOutput.summaryText(0, 60, 800).render(fr))
        val stoppedFr = stopped.copy(summary = TermuxCards.savedText(null, 124).render(fr), detail = TermuxOutput.summaryText(124, 60, 75_000).render(fr))
        rule.show(classic = true) { Cards(listOf(pullFr, scriptFr, doneFr, stoppedFr)) }
        for (b in listOf("Lancer", "Modifier", "Annuler")) rule.onAllNodesWithText(b).onFirst().assertExists()
        rule.onNodeWithText("Utilise Internet").assertExists()
        rule.onNodeWithText("Gemma\u00A0: «\u00A0Gets the latest changes of your notes repository.\u00A0»").assertExists()
        rule.onNodeWithText("Code 0 · 0,8\u00A0s").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("termux_card_fr")
    }
}
