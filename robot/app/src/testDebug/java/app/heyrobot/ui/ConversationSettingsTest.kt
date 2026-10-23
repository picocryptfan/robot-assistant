// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.MtBundle
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceBundle
import app.heyrobot.sys.MtModelStore
import app.heyrobot.sys.VoiceModelStore
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.showSettings
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Conversation: transcribing, speakers, translation, the fast engine, telling people and the transcripts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ConversationSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val snap = Snapshot(
        voice = listOf(VoiceModelStore.Snap(VoiceBundle.SPEAKERS, ModelStatus.MISSING, null, -1, null)),
        mt = MtModelStore.Snap(MtBundle.FR_EN, ModelStatus.MISSING, null, -1, null, engine = true),
    )

    private fun open(start: RobotSettings = RobotSettings()): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> ConversationSettingsScreen(s, snap, u, log.actions, SnackbarHostState(), onBack = {}) }
        return host
    }

    @Test fun theSwitchesAndChoicesWriteTheirSettings() {
        val host = open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        var want = RobotSettings()
        for ((title, change) in listOf<Pair<String, (RobotSettings) -> RobotSettings>>(
            "Live words" to { it.copy(convPartials = false) },
            "“Hey robot” works during a conversation" to { it.copy(convWakeDuring = true) },
            "Start from the lock screen" to { it.copy(convStartLocked = false) },
            "Tell speakers apart" to { it.copy(convSpeakers = false) },
            "Translate transcripts" to { it.copy(convTranslate = true) },
            "Offer a stronger AI first" to { it.copy(convAutoOfferHelper = false) },
        )) {
            rule.scrollToText(title)
            rule.onRow(title).performClick()
            want = change(want)
            assertEquals(title, want, host.last)
        }
        rule.scrollToText("Most speakers in a transcript")
        rule.onRow("Most speakers in a transcript").performClick()
        rule.inDialog("3").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(3, host.last.convMaxSpeakers)
        rule.scrollToText("Summaries in")
        rule.onRow("Summaries in").performClick()
        rule.inDialog("Français").performClick()
        assertEquals("fr", host.last.convSummaryLang)
    }

    @Test fun theDownloadsAndTheVoiceprint() {
        open()
        // Speakers off in the settings would turn its download off too; on by default, it is offered.
        rule.onNodeWithContentDescription("Download the speaker model").performClick()
        rule.scrollToText("Set up your voice")
        rule.onRow("Set up your voice").assertIsNotEnabled().assert(hasText("Needs the speaker model first"))
        rule.scrollToText("Test translation speed")
        rule.onRow("Test translation speed").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Download the fast translation engine").performClick()
        assertEquals(listOf("voiceDownload ${VoiceBundle.SPEAKERS}", "mtDownload"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun theReminderAndTheConsentText() {
        val host = open()
        rule.scrollToText("Show the reminder again")
        rule.onRow("Show the reminder again").performClick()
        assertTrue(host.last.convReminderAt > 0)
        assertEquals("say The reminder shows before the next transcript.", log.last())
        rule.scrollToText(ConsentRecord.TEXT, substring = true)
        rule.scrollToText("biometric data", substring = true)
    }

    @Test fun light() {
        open()
        rule.shot("conversation_light")
    }

    /** 1.1: French menus (values-fr-rCA and core's ConversationStrings): the page, its choices and its dialogs in Québec French. */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun inFrench() {
        val host = open()
        rule.onRow("Mots en direct").assertExists()
        rule.onRow("Distinguer les intervenants").assert(hasText("Il faut le modèle des intervenants (40\u00A0Mo)"))
        rule.onNodeWithContentDescription("Télécharger le modèle des intervenants").assertExists()
        rule.scrollToText("Résumés en")
        rule.onRow("Résumés en").performClick()
        rule.inDialog("Ta langue").assertExists()
        rule.inDialog("English").assertExists()
        rule.inDialog("Français").performClick()
        assertEquals("fr", host.last.convSummaryLang)
        rule.scrollToText("Moteur de traduction rapide (52\u00A0Mo)")
        rule.onNodeWithContentDescription("Télécharger le moteur de traduction rapide").assertExists()
        rule.scrollToText("Afficher le rappel de nouveau")
        rule.onRow("Afficher le rappel de nouveau").performClick()
        assertEquals("say Le rappel s’affichera avant la prochaine transcription.", log.last())
        rule.scrollToText("donnée biométrique", substring = true)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }
}
