// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.guide

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.heyrobot.core.BrainModel
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.MtBundle
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.guide.GuideState
import app.heyrobot.core.guide.GuideStep
import app.heyrobot.core.guide.SetupGuide
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.web.BrowserEngine
import app.heyrobot.sys.Menus
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.MtModelStore
import app.heyrobot.sys.VoiceModelStore
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.GALAXY_S25
import app.heyrobot.ui.testing.PIXEL_10_PRO
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.facts
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
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
 * 1.1: the setup guide with the menus in Québec French (values-fr-rCA/strings_guide.xml), its
 * first page's Language row going to Android's per-app language, the examples French first with the English beside, and
 * the summary's choices in French. 
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
class SetupGuideFrenchTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val nb = ' '

    /** The guide's stores in memory, with the menus' language. */
    private class Stores(start: GuideStep, private var shown: Menus = Menus.FR) : GuideStores {
        var state = SetupGuide.open(GuideState()).copy(at = start)
        val asked = ArrayList<Menus>()
        override fun guide() = state
        override fun saveGuide(s: GuideState) { state = s }
        override fun files() = GuideFiles()
        override fun messages(on: Boolean) {}
        override fun messageApps() = setOf(MessageApp.CHEOGRAM)
        override fun turnMessagesOn(apps: Set<MessageApp>) {}
        override fun announce(on: Boolean) {}
        override fun memory(s: MemorySettings) {}
        override fun rekeyMemory(lockScreen: Boolean) = true
        override fun routines(s: RoutineSettings) {}
        override fun headset(s: HeadsetSettings) {}
        override fun updates(s: UpdateSettings) {}
        override fun browserEngine(e: BrowserEngine) {}
        override fun menus() = shown
        override fun setMenus(m: Menus) { shown = m; asked += m }
    }

    private val fresh = Snapshot(
        facts = facts(roleHeld = false, active = false, micGranted = false, notificationsGranted = false),
        phone = PIXEL_10_PRO,
        models = listOf(ModelStore.Snap(BrainModel.FAST, ModelStatus.MISSING)),
        voice = VoiceBundle.entries.map { VoiceModelStore.Snap(it, ModelStatus.MISSING, null, -1, null) },
        mt = MtModelStore.Snap(MtBundle.FR_EN, ModelStatus.MISSING, null, -1, null, true),
    )

    private fun guide(stores: Stores, snap: Snapshot = fresh, settings: RobotSettings = RobotSettings()) {
        val host = SettingsHost(settings)
        rule.show { SetupGuideScreen(host.now, snap, host::update, log.actions, stores, SnackbarHostState(), onClose = {}) }
    }

    @Test fun welcomeIsFrenchAndChoosesTheMenusLanguage() {
        val stores = Stores(GuideStep.WELCOME)
        guide(stores)
        rule.onNode(hasText("Bienvenue dans Robot™") and isHeading()).assertExists()
        rule.onNodeWithText("Étape 1 sur 18").assertExists()
        rule.onNode(hasText("Suivant") and hasClickAction()).assertExists()
        rule.scrollToText("Menus")
        rule.onRow("Menus").assert(hasStateDescription("Français (Canada)")).performClick()
        rule.inDialog("English").performClick()
        assertEquals(listOf(Menus.EN), stores.asked)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun welcomeScreenshot() {
        guide(Stores(GuideStep.WELCOME))
        rule.shot("guide_welcome_fr")
    }

    @Test fun theExamplesAreFrenchFirst() {
        guide(Stores(GuideStep.CAN_DO))
        rule.onNodeWithText("«${nb}Traduis cette conversation$nb»").assertExists()
        rule.onNodeWithText("“Be my interpreter”").assertExists()
        rule.onAllNodesWithText("Sur le téléphone, même hors ligne").onFirst().assertExists()
    }

    /** 1.2: on a Galaxy S25 the phone page names its Snapdragon and says it has no NPU build, in Québec French. */
    @Test fun thePhonePageNamesASnapdragonInFrench() {
        guide(Stores(GuideStep.PHONE), fresh.copy(phone = GALAXY_S25, models = listOf(ModelStore.Snap(BrainModel.GENERAL, ModelStatus.MISSING))))
        rule.onNodeWithText("Samsung SM-S931W (Snapdragon 8 Elite)").assertExists()
        rule.onNodeWithText("Robot n’a aucune version NPU pour le Snapdragon 8 Elite de ce téléphone$nb: Rapide est la version générale",
            substring = true).assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("guide_phone_s25_fr")
    }

    // 1.2 (design Step 2): the GPU self-test's row, in Québec French.
    private val build = "google/tegu/tegu:16/BP4A.251005.001/14000000:user/release-keys"
    private val nineA = fresh.copy(phone = app.heyrobot.ui.testing.PIXEL_9A, build = build,
        models = listOf(ModelStore.Snap(BrainModel.GENERAL, ModelStatus.VERIFIED)))
    private fun record(verdict: app.heyrobot.core.GpuVerdict, turn: Int = 0) = app.heyrobot.core.CheckRecord(
        app.heyrobot.core.CheckKey.of(BrainModel.GENERAL, app.heyrobot.core.ModelBackend.GPU, BrainModel.GENERAL.sha256, build)!!, verdict, turn,
        1_760_000_000_000L, 41_200, if (verdict.passed) 482.4 else -1.0, if (verdict.passed) 14.3 else -1.0)

    @Test fun theCheckRunsInFrench() {
        guide(Stores(GuideStep.PHONE), nineA.copy(checking = true))
        rule.onNodeWithText("Robot vérifie le GPU de ce téléphone").assertExists()
        rule.onNodeWithText("Trois courtes questions qui ne quittent jamais le téléphone, environ 30${nb}s une fois le modèle chargé. Tu peux continuer le guide.")
            .assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("guide_phone_checking_fr")
    }

    @Test fun aPassedCheckInFrench() {
        guide(Stores(GuideStep.PHONE), nineA.copy(checks = listOf(record(app.heyrobot.core.GpuVerdict.PASSED))))
        rule.onNodeWithText("Le GPU de ce téléphone a réussi la vérification de Robot").assertExists()
        rule.onNodeWithText("Durée$nb: 41${nb}s. Mesuré pendant la vérification$nb: lecture de 482 jetons par seconde, écriture de 14. " +
            "Robot vérifie de nouveau après chaque mise à jour d’Android.").assertExists()
        rule.shot("guide_phone_check_passed_fr")
    }

    @Test fun aFailedCheckInFrench() {
        val gpu = app.heyrobot.core.GpuFailure(BrainModel.GENERAL, 1_760_000_000_000L, app.heyrobot.core.ProblemStage.WRITING, null,
            app.heyrobot.core.SpeedLog.cleanBuild(build), app.heyrobot.core.GpuVerdict.GARBAGE)
        guide(Stores(GuideStep.PHONE), nineA.copy(checks = listOf(record(app.heyrobot.core.GpuVerdict.GARBAGE, 2)), gpuFailure = gpu))
        rule.onNodeWithText("Le GPU de ce téléphone a échoué à la vérification de Robot (réponse brouillée)").assertExists()
        rule.onNodeWithText("Robot fonctionne ici sur le CPU, plus lentement. Pour le vérifier de nouveau, touche «${nb}Réessayer le GPU$nb» dans l’onglet Modèle.")
            .assertExists()
        rule.shot("guide_phone_check_failed_fr")
    }

    @Test fun aCheckWithANoteInFrench() {
        guide(Stores(GuideStep.PHONE), nineA.copy(checks = listOf(record(app.heyrobot.core.GpuVerdict.WRONG, 2))))
        rule.onNodeWithText("Le GPU de ce téléphone a réussi la vérification de Robot, avec une remarque$nb: mauvaise réponse").assertExists()
        rule.onNodeWithText("Durée$nb: 41${nb}s. Rien n’y indique un défaut du GPU, alors Robot continue de l’utiliser. " +
            "Robot vérifie de nouveau après chaque mise à jour d’Android.").assertExists()
        rule.shot("guide_phone_check_noted_fr")
    }

    // 1.2 (design Step 8): the weak-phone warning, in Québec French.
    private val cpuNineA = nineA.copy(checks = listOf(record(app.heyrobot.core.GpuVerdict.GARBAGE, 2)),
        gpuFailure = app.heyrobot.core.GpuFailure(BrainModel.GENERAL, 1_760_000_000_000L, app.heyrobot.core.ProblemStage.WRITING, null,
            app.heyrobot.core.SpeedLog.cleanBuild(build), app.heyrobot.core.GpuVerdict.GARBAGE))

    @Test fun aCpuPhoneIsWarnedInFrench() {
        val first = cpuNineA.fit(true).firstWordS
        guide(Stores(GuideStep.PHONE), cpuNineA, RobotSettings(convTranslate = true))
        rule.scrollToText("Robot sera lent sur ce téléphone")
        rule.onNodeWithText("Le modèle de Robot fonctionne sur le CPU de ce téléphone.").assertExists()
        rule.onNodeWithText("Estimation$nb: une nouvelle conversation commence à répondre en environ $first${nb}s, les réponses suivantes en " +
            "environ 1${nb}s.").assertExists()
        rule.onNodeWithText("Pour répondre plus vite, Robot relit ici moins d’échanges précédents d’une conversation, et donne des consignes " +
            "plus courtes à son modèle.").assertExists()
        rule.onNodeWithText("Une question sur une photo ou sur l’écran prend encore plus de temps$nb: le modèle regarde d’abord l’image.").assertExists()
        rule.scrollToText("Traduire les transcriptions$nb: activé")
        rule.onNodeWithText("Le modèle de Robot traduit aussi chaque ligne, ce qui est lent ici.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("guide_phone_slow_fr")
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Désactiver Traduire les transcriptions")).performClick()
        rule.onNodeWithText("Traduire les transcriptions$nb: activé").assertDoesNotExist()
    }

    @Test @Config(fontScale = 2f)
    fun aCpuPhoneInFrenchAt200() {
        guide(Stores(GuideStep.PHONE), cpuNineA, RobotSettings(convTranslate = true))
        rule.onNode(androidx.compose.ui.test.hasScrollToNodeAction())
            .performScrollToNode(androidx.compose.ui.test.hasContentDescription("Désactiver Traduire les transcriptions"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("guide_phone_slow_font200_fr")
    }

    @Test fun aTooSmallPhoneInFrench() {
        val small = fresh.copy(phone = app.heyrobot.core.Phone("motorola", "moto g", "QTI", "SM6375", 3_700_000_000L),
            models = listOf(ModelStore.Snap(BrainModel.GENERAL, ModelStatus.MISSING)))
        guide(Stores(GuideStep.PHONE), small)
        rule.scrollToText("Pour une question à laquelle il ne peut pas bien répondre, Robot peut consulter une IA plus puissante en ligne. " +
            "Aucune n’est configurée pour l’instant.")
        rule.onNodeWithText("Le modèle de Robot pourrait ne pas tenir dans la mémoire de ce téléphone").assertExists()
        rule.onNodeWithText("Ce téléphone a 3,7${nb}Go de mémoire. Le modèle de Robot en utilise environ 2,6${nb}Go quand il est chargé, et les " +
            "modèles de voix ont aussi besoin de place$nb: il pourrait ne pas se charger ici, ou Android pourrait l’arrêter pendant qu’il répond. " +
            "Robot n’a pas encore de modèle plus petit.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("guide_phone_small_fr")
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Configurer une IA externe")).performClick()
        assertEquals(listOf("openPage Helpers"), log)
    }

    /** 1.2: a phone with its own speech recognizer set isn't asked for the ADB fix (Step 6), in French. */
    @Test fun aPhoneWithItsOwnRecognizerInFrench() {
        val own = fresh.facts!!.copy(recognizer = "com.example.speech/.Recognizer", recognizerIsRobot = false, recognizerInstalled = true)
        guide(Stores(GuideStep.OPENING), snap = fresh.copy(facts = own))
        rule.scrollToText("Rester l\u2019assistant après un redémarrage")
        rule.onNode(hasText("Rester l\u2019assistant après un redémarrage")).assert(hasText("Probablement pas nécessaire sur ce téléphone", substring = true))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("guide_opening_own_recognizer_fr")
    }

    /** 1.2: the brief "after your alarm" without GrapheneOS Clock (Step 6), in French. */
    @Test fun theBriefWithoutClockInFrench() {
        guide(Stores(GuideStep.ROUTINES), snap = fresh.copy(facts = fresh.facts!!.copy(clockInstalled = false)))
        rule.onNodeWithText("Demande l\u2019appli Horloge de GrapheneOS", substring = true).assertExists()
        rule.shot("guide_routines_no_clock_fr")
    }

    @Test fun theSummaryIsFrench() {
        guide(Stores(GuideStep.SUMMARY))
        rule.onNode(hasText("Tout est prêt") and isHeading()).assertExists()
        rule.onNodeWithText("Heart · Réglages › Voix").assertExists()
        rule.onNodeWithText("Demander chaque fois · Réglages › Personnes, notes et agenda").assertExists()
        rule.scrollToText("Demander la première fois · Réglages › Actions")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("guide_summary_fr")
    }
}
