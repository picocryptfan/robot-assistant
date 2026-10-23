// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
import app.heyrobot.ui.testing.ActionLog
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

/**
 * 1.1 (the l10n design's WP2b): Settings › Helpers, a helper's page and Add a helper with French menus: the screens'
 * words (values-fr-rCA/strings_helpers.xml) and core's (HelperStrings: the presets, the voices, the escalation's choices)
 * in Québec French, amounts written the French way, and nothing too small to tap. HelpersScreensTest keeps 1.0's English.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
class HelpersScreensFrenchTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val snap = Snapshot(facts = facts())
    private val nb = ' '

    @Test fun theListInFrench() {
        val host = SettingsHost(RobotSettings(helpers = listOf(HelperConfig.claude())))
        rule.showSettings(host) { s, u -> HelpersScreen(s, snap, u, log.actions, SnackbarHostState(), onBack = {}) }
        rule.onNodeWithText("IA externes").assertExists()
        rule.onRow("Claude").assert(hasText("Anthropic · Claude Opus 5.5", substring = true))
        rule.onRow("Ajouter une IA externe").assertExists()
        rule.onRow("Quand Robot a besoin d’aide").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Demander d’abord · Claude"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Quand Robot a besoin d’aide").performClick()
        rule.inDialog("Robot ne suggère jamais d’IA externe.").assertExists()
        rule.inDialog("Désactivé").performClick()
        rule.scrollToText("Budget quotidien pour toutes les IA externes")
        rule.onRow("Budget quotidien pour toutes les IA externes")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Aujourd’hui$nb: 0,00$nb\$${nb}US sur 1,00$nb\$${nb}US (une estimation)"))
        rule.scrollToText("le reste part seulement après que tu as touché une carte qui l’énumère", substring = true)
    }

    @Test fun aHelpersPageInFrench() {
        val host = SettingsHost(RobotSettings(helpers = listOf(HelperConfig.claude())))
        rule.showSettings(host) { s, u -> HelperScreen(HelperConfig.CLAUDE_ID, s, snap, u, log.actions, SnackbarHostState(), onBack = {}) }
        rule.onRow("Utiliser Claude").assertExists()
        rule.onRow("Ajouter une clé").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.scrollToText("Voix de Claude")
        rule.onRow("Voix de Claude").performClick()
        rule.inDialog("Emma (britannique, femme)").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals("bf_emma", host.last.claude!!.voice)
        rule.scrollToText("Retirer Claude")
        rule.scrollToText("Active-le seulement pour une IA externe à qui tu montrerais ton agenda", substring = true)
    }

    // The address dialog has a text field: Robolectric settles with one only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "fr-rCA-w320dp-h640dp-mdpi")
    fun addingAHelperInFrench() {
        val host = SettingsHost()
        rule.showSettings(host) { s, u -> AddHelperScreen(s, u, log.actions, SnackbarHostState(), onAdded = {}, onBack = {}) }
        rule.onRow("OpenRouter (des centaines de modèles)").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.scrollToText("Ton propre serveur (Ollama, LM Studio, llama.cpp, vLLM…)")
        rule.onRow("Ton propre serveur (Ollama, LM Studio, llama.cpp, vLLM…)").performClick()
        rule.inDialog("L’adresse de ton serveur").assertExists()
        rule.inDialog("Annuler").performClick()
        assertEquals(Presets.ALL.size, Presets.ALL.map { it.titleText }.toSet().size)
    }
}
