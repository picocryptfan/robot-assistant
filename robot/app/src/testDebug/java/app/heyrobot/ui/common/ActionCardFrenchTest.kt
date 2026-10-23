// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.FrTypo
import app.heyrobot.ui.classic.ClassicActionCard
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
 * 1.1 (the l10n design's WP1): the action card with the menus in Québec French. Its buttons, its state and its hints are
 * the menus' language (values-fr-rCA/strings_cards.xml, core's card states through LocalLangs); its summary and line are
 * what the turn said, as they come. Still usable: the longer French words keep 48 dp targets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
class ActionCardFrenchTest {
    @get:Rule val rule = createComposeRule()

    private val log = ArrayList<String>()
    private val more = CardActions(choose = { id, i, keep -> log += "choose $id $i $keep" }, undo = { log += "undo $it" }, allow = { log += "allow" })
    private val confirm: (String, Boolean) -> Unit = { id, yes -> log += "confirm $id $yes" }

    private val text = ActionView("a1", ToolName.SEND_TEXT, "Texter **Maman** dans Cheogram", ActionStatus.PENDING, null, body = "J'arrive dans 10 minutes.")
    private val pick = text.copy(id = "a2", choices = listOf("Maman · Cellulaire", "Maman · Travail"), more = 3)
    private val done = ActionView("a3", ToolName.SET_TIMER, "Régler une minuterie de 10 minutes", ActionStatus.DONE, null)
    private val note = ActionView("a4", ToolName.ADD_NOTE, "Enregistrer une note dans Notes de Robot", ActionStatus.DONE, "Enregistré dans Notes de Robot.", undo = true)
    private val noContacts = ActionView("a5", ToolName.SEND_TEXT, "Texter **Alex**", ActionStatus.NEEDS_PERMISSION, null)

    @Test fun theButtonsAndStatesAreFrench() {
        rule.show(classic = true) { Column(Modifier.padding(16.dp)) { ClassicActionCard(text, confirm, more); ClassicActionCard(done, confirm, more); ClassicActionCard(note, confirm, more) } }
        rule.onNodeWithText("Veux-tu que Robot fasse ceci ?").assertExists()
        rule.onNodeWithText("Fait").assertExists()
        rule.onAllNodesWithContentDescription("Fait").assertCountEquals(2)
        // The waiting card's Cancel and the note's Undo are both « Annuler », never on the same card (the glossary).
        rule.onAllNodesWithText("Annuler").assertCountEquals(2)
        rule.onNodeWithText("Fais-le").performClick()
        rule.onNodeWithText("Enregistré dans Notes de Robot.").assertExists()
        assertEquals(listOf("confirm a1 true"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /**
     * WP9b: ActionCardTest's screenshots with the menus in French. The summaries are what core says in French (FrTypo puts
     * its spaces in, as render() does); the buttons, states and hints are the menus'.
     */
    private val shotText = ActionView("a1", ToolName.SEND_TEXT, "Texter **Maman** dans Cheogram", ActionStatus.PENDING, null,
        body = FrTypo.fix("Je suis en retard, on se voit à 19 h."))
    private val shotPick = shotText.copy(id = "a2", choices = listOf("Maman · Cellulaire", "Maman · Travail"), more = 3,
        remember = FrTypo.fix("Toujours utiliser ceci pour « maman »"))
    private val shotNote = ActionView("a4", ToolName.ADD_NOTE, FrTypo.fix("Note : acheter des filtres à café"), ActionStatus.DONE, "Enregistrée", undo = true)
    private val shotNoContacts = ActionView("a5", ToolName.SEND_TEXT, "Texter **Alex**", ActionStatus.NEEDS_PERMISSION,
        FrTypo.fix("L'accès aux contacts n'est pas autorisé"))
    private val shotInclude = ActionView("a6", ToolName.ASK_HELPER, FrTypo.fix("Inclure la conversation jusqu'ici ?"), ActionStatus.PENDING,
        FrTypo.fix("Repartir à zéro : Claude voit seulement ce que tu demandes à partir de maintenant."), confirm = "Inclure", cancel = "Repartir à zéro")

    @Test fun light() {
        rule.show(classic = true) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (a in listOf(shotText, done, shotNote, shotNoContacts, shotInclude)) ClassicActionCard(a, confirm, more)
            }
        }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("action_cards_fr")
    }

    @Test @Config(fontScale = 2f)
    fun atTwiceTheTextSize() {
        rule.show(classic = true) { Column(Modifier.padding(16.dp)) { ClassicActionCard(shotPick, confirm, more); ClassicActionCard(shotText, confirm, more) } }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("action_cards_fr_font200")
    }

    @Test fun aCardToChooseFromAndOneThatNeedsPermission() {
        rule.show(classic = true) { Column(Modifier.padding(16.dp)) { ClassicActionCard(pick, confirm, more); ClassicActionCard(noContacts, confirm, more) } }
        rule.onNodeWithText("Lequel ?").assertExists()
        rule.onNodeWithText("…et 3 autres. Dis le nom complet pour en choisir un.").assertExists()
        rule.onNodeWithText("Ton autorisation est requise").assertExists()
        rule.onNodeWithText("Autoriser").performClick()
        rule.onNodeWithText("Maman · Travail").performClick()
        assertEquals(listOf("allow", "choose a2 1 false"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }
}
