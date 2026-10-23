// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.LookSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.1 (the l10n design's WP2a): the panel with the menus in Québec French, in its one look (1.0). Its buttons, hints, status
 * line and field are the menus' language (values-fr-rCA/strings_panel.xml, core PanelStrings through LocalLangs); the turns
 * are what was said, as they come (the fixtures' English). Still usable: 48 dp targets with the longer words. The
 * screenshots are panel_classic_idle_fr and panel_classic_answer_card_fr (1.0 removed the standard panel and its two).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
class PanelFrenchTest {
    @get:Rule val rule = createComposeRule()

    private val fine = ' '
    private val nb = ' '

    private fun noProblems() = assertEquals(emptyList<String>(), rule.touchTargetProblems())

    @Test fun aWaitingCardKeepsItsWordsAndGetsFrenchButtons() {
        val a = RecordingActions()
        rule.showPanel(PanelFixtures.answerWithCard, a)
        for (label in listOf("Parler à Robot", "Fermer", "Nouvelle conversation", "Réglages", "Lire les réponses à voix haute")) rule.onNode(hasContentDescription(label)).assertExists()
        rule.onNodeWithText("Annuler").assertExists()
        rule.onNodeWithText("Fais-le").performClick()
        assertEquals(listOf("confirm a1 true"), a.calls)
        noProblems()
    }

    @Test fun cardWithChoices() { rule.showPanel(PanelFixtures.choices); noProblems() }

    /** 1.4 F6: "classe ça": the file's card with Déplacer and Annuler, 48 dp targets. */
    @Test fun filedWithMoveAndUndo() {
        rule.showPanel(PanelFixtures.filed(fr = true))
        noProblems()
        rule.onNodeWithText("Déplacer").assertExists()
        rule.shot("panel_files_filed_fr")
    }

    /** WP9b: the answer with its card, as PanelLookTest's panel_classic_answer_card, with the menus in French. */
    @Test fun anAnswerWithItsCard() {
        rule.showPanel(PanelFixtures.choices)
        noProblems()
        rule.onNodeWithText("Annuler").assertExists()
        rule.shot("panel_classic_answer_card_fr")
    }

    @Test fun theClassicLookIsFrench() {
        rule.showPanel(PanelFixtures.idle)
        rule.onNodeWithText("Privé, sur l’appareil").assertExists()
        rule.onNodeWithText("Demande-moi n’importe quoi.").assertExists()
        rule.onNode(hasContentDescription("Parler à Robot")).assertExists()
        rule.onNode(hasContentDescription("Lire les réponses à voix haute")).assertExists()
        rule.shot("panel_classic_idle_fr")
    }

    /** The status line under the header, and a notice's button, as the panel's pure functions give them. */
    @Test fun theWordsOfThePanelsStates() {
        val fr = Langs.of(Lang.FR)
        val base = PanelState("c1")
        assertEquals("Avec Claude${nb}: tes questions vont en ligne", statusLine(base.copy(helperMode = "claude", helperName = "Claude"), fr))
        assertEquals("À l’écoute…", statusLine(base.copy(phase = Phase.LISTENING), fr))
        assertEquals("Ouvrir les paramètres", noticeAction(NoticeAction.OPEN_ASSIST_SETTINGS, RecordingActions(), fr)?.first)
        // English is 1.0's.
        assertEquals("Listening…", statusLine(base.copy(phase = Phase.LISTENING)))
    }
}
