// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import app.heyrobot.core.AdbFixes
import app.heyrobot.core.Approval
import app.heyrobot.core.LookSettings
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.ToolName
import app.heyrobot.core.WakePhrase
import app.heyrobot.core.WakeSensitivity
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackFacts
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.update.FileRef
import app.heyrobot.core.update.FoundRelease
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.ui.AdbFixPage
import app.heyrobot.ui.SafetyScreen
import app.heyrobot.ui.ShizukuUi
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PIXEL_10_PRO
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.facts
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
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
 * 1.1 (the l10n design's WP8): Settings with the menus in Québec French. The pages' words are values-fr-rCA's
 * (strings_settings.xml, strings_updates.xml, strings_adb.xml), the rows' states and the choices core's catalog through
 * LocalLangs (SettingsStrings, UpdateStrings, FeedbackStrings, UiStrings for the titles), Android's screens in Android's
 * words; still 48 dp targets with the longer words. The screenshots are settings_root_fr, safety_fr and
 * prefs_fr_font200 (a page of every kind of row, at twice the text size).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
class SettingsFrenchTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val nb = ' '
    private val fine = ' '
    private val fr = Langs.of(Lang.FR)

    private fun noProblems() = assertEquals(emptyList<String>(), rule.touchTargetProblems())

    @Test fun theRootIsFrench() {
        rule.show { SettingsRoot(RobotSettings(), Snapshot(facts = facts(), phone = PIXEL_10_PRO), log.actions, look = LookSettings(), memory = MemorySettings()) }
        rule.onRow("Mot d’éveil").assert(hasText("Activé · «${nb}hey robot$nb»"))
        rule.onRow("Voix").assert(hasText("Heart · parle quand tu parles"))
        for (title in listOf("Actions", "Sécurité et confidentialité", "Routines", "Compétences", "Écouteurs", "Mode auto", "Personnes, notes et agenda", "Messages",
            "Météo", "Recherche web", "IA externes", "Conversation", "Termux", "Modèle", "Mémoire", "Historique", "Affichage", "Langue",
            "Configuration de l’assistant", "Avancé", "À propos")) {
            rule.scrollToText(title)
            rule.onRow(title).assertExists()
        }
        rule.onRow("Historique").assert(hasText("Conversations enregistrées"))
        rule.onRow("Avancé").assert(hasText("Correctifs ADB, journal vocal"))
        // 1.4: Settings › Files.
        rule.scrollToText("Fichiers")
        rule.onRow("Fichiers").assert(hasText("Aucun fichier pour l’instant"))
        rule.onAllNodesWithText("Réglages").onFirst().assertExists()
        noProblems()
    }

    @Test fun theRootsStatesInFrench() {
        val s = RobotSettings(
            wakePhrases = listOf(WakePhrase(1, "hey robot", WakeSensitivity.MEDIUM, true), WakePhrase(2, "computer", WakeSensitivity.LOW, true),
                WakePhrase(3, "jarvis", WakeSensitivity.LOW, true)),
            speakReplies = SpeakReplies.ALWAYS, approvals = RobotSettings.DEFAULT_APPROVALS + (ToolName.FLASHLIGHT to Approval.OFF),
            convTranslate = true,
        )
        assertEquals("Activé · «${nb}hey robot$nb» et 2 autres", wakeSummaryText(s).render(fr))
        assertEquals("Activé · «${nb}hey robot$nb» et 1 autre", wakeSummaryText(s.copy(wakePhrases = s.wakePhrases.take(2))).render(fr))
        assertEquals("Désactivé", wakeSummaryText(s.copy(wakeWord = false)).render(fr))
        assertEquals("Heart · parle toujours", voiceSummaryText(s).render(fr))
        assertEquals("6 demandent d’abord · 1 désactivée", actionsSummaryText(s).render(fr))
        assertEquals("Mots en direct activés · traduction activée", conversationSummaryText(s).render(fr))
        assertEquals("Appli SMS · Demander chaque fois", peopleSummaryText(s, facts()).render(fr))
        assertEquals("Aucune", helpersSummaryText(s, facts()).render(fr))
        assertEquals("Claude · Demander d’abord", helpersSummaryText(s.copy(helpers = listOf(app.heyrobot.core.helpers.HelperConfig.claude())), facts()).render(fr))
        assertEquals("Couleurs de Robot", displaySummaryText(LookSettings(wallpaperColors = false)).render(fr))
        assertEquals("Faire sans demander · fonctionne verrouillé", approvalSummaryText(ToolName.SET_TIMER, Approval.AUTO, true).render(fr))
        // 1.4 F13: Settings › Files' row while travel mode is on (no counts), and the page by its path; the page itself is
        // FilesSettingsTest's inFrench, the Files tab FilesScreenTest's inQuebecFrench.
        assertEquals("Le mode voyage est activé", app.heyrobot.core.i18n.FilesStrings.travelOnRow().render(fr))
        assertEquals("Réglages › Fichiers", app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.files).render(fr.ui, fr.medium, fr.pseudo))
        // The English is 1.0's, word for word.
        assertEquals("On · “hey robot” and 2 others", wakeSummary(s))
        assertEquals("6 ask first · 1 off", actionsSummary(s))
    }

    @Test fun rootScreenshot() {
        rule.show { SettingsRoot(RobotSettings(), Snapshot(facts = facts(), phone = PIXEL_10_PRO), log.actions, look = LookSettings(), memory = MemorySettings()) }
        rule.shot("settings_root_fr")
    }

    private fun safety() = rule.showSettings(SettingsHost()) { s, u -> SafetyScreen(s, u, log.actions, SnackbarHostState(), onBack = {}) }

    @Test fun safetyIsFrench() {
        safety()
        rule.onRow("Quand Robot demande d’abord").assertExists()
        rule.onRow("Répondre aux cartes à voix haute").assert(hasText("Dis oui ou non pendant qu’une carte attend"))
        rule.scrollToText("Fonctionne sur l’écran de verrouillage")
        rule.scrollToText("Minuteries")
        rule.onRow("Minuteries").assert(hasText("Activé par défaut"))
        rule.scrollToText("Envoyer du contenu personnel aux IA externes automatiquement")
        rule.scrollToText("Garder les captures d’écran et les photos dans l’historique")
        noProblems()
        rule.shot("safety_fr")
    }

    @Test @Config(fontScale = 2f)
    fun safetyAtTwiceTheTextSize() {
        safety()
        noProblems()
        rule.shot("prefs_fr_font200")
    }

    @Test fun actionsAndWeatherAreFrench() {
        rule.showSettings(SettingsHost()) { s, u -> ActionsSettingsScreen(s, Snapshot(facts = facts(), phone = PIXEL_10_PRO), u, log.actions, SnackbarHostState(), onBack = {}) }
        rule.onNodeWithText("Ce que Robot peut faire quand tu le demandes.").assertExists()
        rule.onRow("Minuteries").assert(hasStateDescription("Faire sans demander · fonctionne verrouillé"))
        rule.scrollToText("Trouver l’endroit le plus proche en ligne")
        rule.onRow("Trouver l’endroit le plus proche en ligne").assert(hasStateDescription("Demander la première fois"))
        noProblems()
    }

    /** 1.1, Skills (plan §9): the list and a skill's page with the menus in French; your names and steps' values stay yours. */
    @Test fun skillsAreFrench() {
        val now = java.time.ZonedDateTime.of(2026, 10, 19, 18, 0, 0, 0, java.time.ZoneId.of("America/Toronto"))
        val s = app.heyrobot.core.skills.Skill("s-k3f9q2", 1, "Je rentre", emptyList(), listOf("je rentre"),
            listOf(app.heyrobot.core.skills.SkillParam("who", app.heyrobot.core.skills.ParamType.PERSON, "Sam", "Who should I tell?", "À qui je le dis?")),
            listOf(app.heyrobot.core.skills.SkillStep.of(app.heyrobot.core.skills.StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "je m'en viens")),
                app.heyrobot.core.skills.SkillStep.of(app.heyrobot.core.skills.StepKind.MAPS_NAVIGATE, mapOf("destination" to "home"))),
            app.heyrobot.core.skills.SkillLevel.ASK_FIRST, app.heyrobot.core.skills.SkillProvenance(app.heyrobot.core.skills.SkillMade.VOICE, false, 1L, 1L))
        val data = SkillsData(listOf(s))
        rule.show { SkillsScreen(data, SkillActions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.onNodeWithText("Tes compétences").assertExists()
        rule.onRow("Je rentre").assert(hasText("Demander d’abord · Jamais lancée"))
        noProblems()
    }

    @Test fun aSkillsPageIsFrench() {
        val now = java.time.ZonedDateTime.of(2026, 10, 19, 18, 0, 0, 0, java.time.ZoneId.of("America/Toronto"))
        val s = app.heyrobot.core.skills.Skill("s-k3f9q2", 1, "Je rentre", emptyList(), listOf("je rentre"),
            listOf(app.heyrobot.core.skills.SkillParam("who", app.heyrobot.core.skills.ParamType.PERSON, "Sam", "Who should I tell?", "À qui je le dis?")),
            listOf(app.heyrobot.core.skills.SkillStep.of(app.heyrobot.core.skills.StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "je m'en viens")),
                app.heyrobot.core.skills.SkillStep.of(app.heyrobot.core.skills.StepKind.MAPS_NAVIGATE, mapOf("destination" to "home"))),
            app.heyrobot.core.skills.SkillLevel.ASK_FIRST, app.heyrobot.core.skills.SkillProvenance(app.heyrobot.core.skills.SkillMade.VOICE, false, 1L, 1L))
        val data = SkillsData(listOf(s))
        rule.show { SkillPageScreen(s, data, SkillActions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.onRow("Niveau").assert(hasStateDescription("Demander d’abord"))
        rule.scrollToText("Étapes")
        rule.onNodeWithText("2. Ouvrir l’itinéraire vers la maison dans Organic Maps").assertExists()
        rule.scrollToText("Supprimer la compétence")
        noProblems()
    }

    @Test fun displayAndHistoryAreFrench() {
        rule.show { DisplaySettingsScreen(LookSettings(), {}, onBack = {}) }
        rule.onRow("Utiliser les couleurs du fond d’écran").assert(hasText("Assorties à ton fond d’écran"))
        rule.onNodeWithText("Clair ou sombre suit ton téléphone.").assertExists()
    }

    @Test fun updatesAreFrench() {
        val own = GitHubRepo("picocryptfan", "robot-gos-assistant")
        val offer = FoundRelease("picocryptfan/robot-gos-assistant", "v0.4.6", "0.4.6", false, "2026-10-11T12:00:00Z", "",
            FileRef(101, "Robot-0.4.6.apk", 97_000_000), FileRef(102, "SHA256SUMS", 250))
        rule.show {
            UpdatesScreen(UpdatesUi(installed = "0.4.5", settings = UpdateSettings(), repo = own, defaultRepo = own, lastCheck = "", offer = offer),
                UpdatesActions(), onBack = {})
        }
        rule.onNodeWithText("Vérifier maintenant").assertExists()
        rule.onNodeWithText("dimanche 11 octobre 2026 · 97${nb}Mo").assertExists()
        rule.onNodeWithText("Ignorer cette version").assertExists()
        rule.onNodeWithText("La voir sur GitHub").assertExists()
        rule.onNodeWithText("Permettre à Robot d’installer les mises à jour").assert(
            hasText("Paramètres › Applications › Accès spécial des applications › Installer des applis inconnues › Robot", substring = true))
        noProblems()
    }

    @Test fun feedbackIsFrench() {
        val facts = FeedbackFacts("0.7.0 (70) · release build", "Pixel 9a", "Fast · Gemma 4 E2B · GPU", "stock", "fr-CA")
        rule.show {
            FeedbackScreen(FeedbackArgs(FeedbackEntry.ABOUT, FeedbackKind.BUG, null), FeedbackUi(facts, emptyList()), FeedbackActions(), onBack = {})
        }
        for (k in listOf("Bogue", "Idée", "Autre")) rule.onNodeWithText(k).assertExists()
        rule.onNodeWithText("Qu’est-ce qui s’est passé$fine?").assertExists()
        rule.onNodeWithText("Étapes (facultatif)").assertExists()
        rule.onNode(hasContentDescription("Plus d’options")).assertExists()
        noProblems()
    }

    @Test fun anAdbFixIsFrench() {
        val fix = AdbFixes.ALL.first { it.id == "repair" }
        rule.show { AdbFixPage(fix, emptyMap(), ShizukuUi(offered = false), SnackbarHostState(), onBack = {}) {} }
        rule.onAllNodesWithText("Laisser Robot réparer ça lui-même").onFirst().assertExists()
        rule.onNodeWithText("Pas fait").assertExists()
        rule.onNodeWithText("Pourquoi").assertExists()
        rule.onNodeWithText("Avec un ordinateur (adb)").assertExists()
        noProblems()
    }
}
