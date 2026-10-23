// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.heyrobot.core.BargeIn
import app.heyrobot.core.InterruptSensitivity
import app.heyrobot.core.ListenPause
import app.heyrobot.core.Playback
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.StopKey
import app.heyrobot.core.VoiceChoice
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.heyrobot.title

/** Settings › Voice: every choice is a radio dialog that closes on a pick and writes only its own setting. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class VoiceSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()

    private fun open(start: RobotSettings = RobotSettings(), snap: Snapshot = Snapshot()): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> VoiceSettingsScreen(s, snap, u, log.actions, SnackbarHostState(), onBack = {}) }
        return host
    }

    /**
     * 1.2 (every-android-phone, Step 6): the phone's voice is named as Android names its text-to-speech engine (a made-up
     * engine here, as sys PhoneVoice would read it), never GrapheneOS's on every phone.
     */
    @Test fun thePhonesVoiceIsNamedByItsEngine() {
        open(snap = Snapshot(systemVoice = "Example Speech Engine"))
        rule.onRow("Voice").performClick()
        rule.inDialog("Example Speech Engine").assertExists()
        rule.onNodeWithText("GrapheneOS", substring = true).assertDoesNotExist()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("voice_choice_engine")
    }

    /** No engine Android would name (Robolectric has none): the plain fallback, in French here. */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun thePhonesVoiceWithoutANameInFrench() {
        open()
        rule.onRow("Voix").performClick()
        rule.inDialog("Le moteur de synthèse vocale du téléphone").assertExists()
        rule.inDialog("Voix du téléphone").assertExists()
        rule.shot("voice_choice_engine_fr")
    }

    /** Opens [row]'s dialog, picks [choice], and checks the dialog closed. */
    private fun pick(row: String, choice: String) {
        rule.scrollToText(row)
        rule.onRow(row).performClick()
        rule.onNode(isDialog()).assertExists()
        assertEquals(row, emptyList<String>(), rule.touchTargetProblems())
        rule.inDialog(choice).performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun eachChoiceWritesItsOwnSetting() {
        val host = open()
        var want = RobotSettings()
        pick("Voice", VoiceChoice.KITTEN.title); want = want.copy(voice = VoiceChoice.KITTEN.id); assertEquals(want, host.last)
        pick("Speak replies", "Always"); want = want.copy(speakReplies = SpeakReplies.ALWAYS); assertEquals(want, host.last)
        pick("Hear you while Robot talks", BargeIn.ALWAYS.title); want = want.copy(bargeIn = BargeIn.ALWAYS); assertEquals(want, host.last)
        pick("Voice plays as", Playback.CALL.title); want = want.copy(playback = Playback.CALL); assertEquals(want, host.last)
        pick("Volume key stops Robot", StopKey.EITHER.title); want = want.copy(stopKey = StopKey.EITHER); assertEquals(want, host.last)
        rule.scrollToText("Show words while you speak")
        rule.onNodeWithText("Show words while you speak").performClick(); want = want.copy(livePartials = false); assertEquals(want, host.last)
        pick("Wait before answering", "Very long"); want = want.copy(listenPause = ListenPause.VERY_LONG); assertEquals(want, host.last)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Interrupt sensitivity"))
        rule.onNodeWithContentDescription("Interrupt sensitivity").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        want = want.copy(interrupt = InterruptSensitivity.HIGH); assertEquals(want, host.last)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Speed"))
        rule.onNodeWithContentDescription("Speed").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.0×"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        want = want.copy(speechRate = 1.5f); assertEquals(want, host.last)
        assertEquals(9, host.saved.size)
    }

    @Test fun theRowsAreLabelledAndTheToolsOpen() {
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Download voice models").assertExists()
        rule.onNodeWithText("Open").performClick()
        for (t in listOf("Test the voice", "Test voice speed", "Barge-in test", "Transcription check", "Voice log")) {
            rule.scrollToText(t)
            assertEquals(t, emptyList<String>(), rule.touchTargetProblems())
            rule.onRow(t).performClick()
        }
        assertEquals(listOf("openPage ${Page.Settings(SettingsRoutes.MODEL)}", "speakTest", "voiceBench", "openPage ${Page.Settings(SettingsRoutes.BARGE_IN)}",
            "openTranscriptionCheck", "openPage ${Page.VoiceLog}"), log)
        rule.scrollToText("back to the best mode for questions", substring = true)
    }

    @Test fun light() {
        open()
        rule.shot("voice_light")
    }

    /** Fix 1.1: the voice dialog says what Siwis reads in 1.1 (French replies with Heart too, and English when picked). */
    @Test fun theVoiceDialogSaysWhatSiwisReads() {
        open(RobotSettings(voice = VoiceChoice.SIWIS.id))
        rule.onRow("Voice").performClick()
        rule.inDialog("it reads French replies with Heart too and, picked here, reads English with French pronunciation", substring = true).assertExists()
        rule.shot("voice_dialog")
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun theVoiceDialogInFrench() {
        open(RobotSettings(voice = VoiceChoice.SIWIS.id))
        rule.onRow("Voix").performClick()
        rule.inDialog("elle lit aussi les réponses en français avec Heart et, choisie ici, lit l\u2019anglais avec une prononciation française",
            substring = true).assertExists()
        rule.shot("voice_dialog_fr")
    }

    @Test @Config(fontScale = 2f)
    fun atTwiceTheTextSize() {
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("voice_font200")
    }

    /**
     * 1.1: French menus (values-fr-rCA/strings_voice.xml, core's VoiceStrings and UiStrings): the page, its choices and its
     * tools in Québec French; the values other areas name (a voice, how the voice plays) stay theirs.
     */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun inFrench() {
        val host = open()
        rule.onNodeWithText("Télécharger les modèles de voix").assertExists()
        rule.onNodeWithText("Ouvrir").assertExists()
        rule.onNodeWithText("La voix de Robot").assertExists()
        pick("Lire les réponses à voix haute", "Toujours")
        assertEquals(SpeakReplies.ALWAYS, host.last.speakReplies)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Vitesse"))
        rule.onNodeWithContentDescription("Vitesse").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1,0×"))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Sensibilité d\u2019interruption"))
        for (t in listOf("Tester la voix", "Tester la vitesse de la voix", "Test d\u2019interruption", "Vérification de la transcription", "Journal vocal")) {
            rule.scrollToText(t)
            assertEquals(t, emptyList<String>(), rule.touchTargetProblems())
        }
        rule.scrollToText("revient au meilleur mode pour les questions", substring = true)
    }
}
