// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.model

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.heyrobot.core.BrainModel
import app.heyrobot.core.GpuFailure
import app.heyrobot.core.LiteRtLmReport
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelProblem
import app.heyrobot.core.ProblemStage
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.ModelTerms
import app.heyrobot.core.OwnModel
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeedKey
import app.heyrobot.core.SpeedSample
import app.heyrobot.core.VoiceBundle
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.VoiceModelStore
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.settings.SettingsNav
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.GALAXY_S25
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.PIXEL_10_PRO
import app.heyrobot.ui.testing.PIXEL_9A
import app.heyrobot.ui.testing.PIXEL_11
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
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
import app.heyrobot.gb
import app.heyrobot.title

/**
 * The Model tab (radio rows with a details button each, thinking, memory, the voice models) and its pages: a model's
 * Download and Import (with its publisher's terms first when it has them), your own file's TPU dialog, a voice model's Delete.
 * 0.4.7's parts: only this phone's Fast, this phone and its chip, "Use the GPU" and "Try the GPU again" on a Pixel 9a, the
 * last model problem's report, the memory warning.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ModelPagesTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val sha = "a".repeat(64)
    private val tpuBuild = OwnModel("gemma-own.litertlm", 3_000_000_000L, sha, "fp",
        LiteRtLmReport(readable = true, decoder = true, aux = true, dispatchOp = true, stamp = "Google Tensor_G5", modelType = "gemma4"))
    /** A Pixel 10 Pro: Fast is the Tensor G5's TPU build, and your own TPU build may run there. */
    private val snap = Snapshot(
        phone = PIXEL_10_PRO,
        freeBytes = 50_000_000_000L,
        models = listOf(
            ModelStore.Snap(BrainModel.FAST, ModelStatus.VERIFIED, fileBytes = BrainModel.FAST.bytes),
            ModelStore.Snap(BrainModel.SMARTER, ModelStatus.MISSING),
            ModelStore.Snap(BrainModel.UNCENSORED, ModelStatus.MISSING),
            ModelStore.Snap(BrainModel.OWN, ModelStatus.VERIFIED, fileBytes = tpuBuild.bytes),
        ),
        own = tpuBuild,
        voice = VoiceBundle.entries.map { VoiceModelStore.Snap(it, if (it == VoiceBundle.STT) ModelStatus.VERIFIED else ModelStatus.MISSING, null, -1, null) },
    )

    /**
     * 0.4.7: a Pixel 9a (Tensor G4, under 8 GB): Fast is Gemma 4 E2B's general build, checked here, on the GPU unless it
     * failed there; the same own TPU build can't run here.
     */
    private val nineA = snap.copy(
        phone = PIXEL_9A,
        models = listOf(
            ModelStore.Snap(BrainModel.GENERAL, ModelStatus.VERIFIED, fileBytes = BrainModel.GENERAL.bytes),
            ModelStore.Snap(BrainModel.SMARTER, ModelStatus.MISSING),
            ModelStore.Snap(BrainModel.UNCENSORED, ModelStatus.MISSING),
            ModelStore.Snap(BrainModel.OWN, ModelStatus.VERIFIED, fileBytes = tpuBuild.bytes),
        ),
    )
    private val problem = ModelProblem(1_760_000_000_000L, BrainModel.GENERAL, ModelBackend.GPU, ProblemStage.WRITING, null,
        BrainModel.GENERAL, ModelBackend.CPU)

    private fun tab(start: RobotSettings = RobotSettings(), snap: Snapshot = this.snap): SettingsHost {
        val host = SettingsHost(start)
        rule.show { ModelScreen(host.now, host::update, snap, null, log.actions) }
        return host
    }

    private fun page(route: String, start: RobotSettings = RobotSettings(), snap: Snapshot = this.snap): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> SettingsNav(route, s, snap, emptyList(), u, log.actions, SnackbarHostState(), onBack = { log += "back" }) }
        return host
    }

    @Test fun robotUsesIsRadioRowsWithADetailsButtonEach() {
        tab()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Fast").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsSelected()
        rule.onRow("Smarter, slower").assertIsNotEnabled().assert(hasText("Not downloaded", substring = true))
        // Your own file is checked but a TPU build waits for you to allow the TPU.
        rule.onRow(BrainModel.OWN.title).assertIsNotEnabled().assert(hasText("Allow the TPU for it first", substring = true))
        rule.onNodeWithContentDescription("Smarter, slower details").performClick()
        assertEquals(listOf("openPage ${Page.Settings(ModelRoutes.model(BrainModel.SMARTER))}"), log)
    }

    @Test fun thinkingMemoryAndTheVoiceModels() {
        val host = tab()
        rule.scrollToText("Let the model think first")
        rule.onRow("Let the model think first").performClick()
        assertEquals(RobotSettings(modelThinks = true), host.last)
        rule.scrollToText("Keep the model loaded")
        rule.onRow("Keep the model loaded").performClick()
        rule.inDialog("Always").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(RobotSettings.KEEP_ALWAYS, host.last.keepModelMinutes)
        rule.scrollToText(VoiceBundle.KOKORO.title)
        rule.onRow(VoiceBundle.KOKORO.title).performClick()
        assertEquals("openPage ${Page.Settings(ModelRoutes.voice(VoiceBundle.KOKORO))}", log.last())
        rule.scrollToText("About the downloads", substring = true)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun aModelPageDownloads() {
        page(ModelRoutes.model(BrainModel.SMARTER))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Download ${ModelLogic.gb(BrainModel.SMARTER.bytes)}").performClick()
        rule.onRow("Import a file").performClick()
        assertEquals(listOf("download ${BrainModel.SMARTER}", "import ${BrainModel.SMARTER}"), log)
        rule.scrollToText(BrainModel.SMARTER.sha256!!, substring = true)
    }

    @Test fun theUncensoredModelAsksItsTermsFirst() {
        val host = page(ModelRoutes.model(BrainModel.UNCENSORED))
        rule.onRow("Download ${ModelLogic.gb(BrainModel.UNCENSORED.bytes)}").performClick()
        rule.onNode(isDialog()).assertExists()
        val terms = ModelTerms.of(BrainModel.UNCENSORED)!!
        rule.inDialog("Confirm and download").assertIsNotEnabled()
        for (t in terms.ticks) rule.inDialog(t).performClick()
        rule.inDialog("Confirm and download").assertIsEnabled().performClick()
        assertTrue(host.last.confirmedTerms.containsKey(terms.id))
        assertEquals(listOf("download ${BrainModel.UNCENSORED}"), log)
    }

    @Test fun yourOwnTpuBuildRunsOnTheTpuOnlyFromItsDialog() {
        page(ModelRoutes.model(BrainModel.OWN))
        rule.scrollToText("Run on the TPU")
        rule.onRow("Run on the TPU").performClick()
        rule.onNode(isDialog()).assertExists()
        rule.inDialog("hang the TPU until the phone restarts", substring = true).assertExists()
        rule.inDialog("Cancel").performClick()
        assertTrue(log.isEmpty())
        rule.onRow("Run on the TPU").performClick()
        rule.inDialog("Run on the TPU").performClick()
        assertEquals(listOf("allowOwnTpu true $sha"), log)
    }

    /**
     * 1.0 audit (native-01, ipc-5): importing your own file warns first that a .litertlm runs with Robot's own
     * permissions; Cancel picks nothing, Choose a file opens the picker.
     */
    @Test fun importingYourOwnFileWarnsFirst() {
        page(ModelRoutes.model(BrainModel.OWN))
        rule.onRow("Import another file").performClick()
        rule.inDialog("runs with Robot's own permissions", substring = true).assertExists()
        rule.inDialog("Only import files you trust", substring = true).assertExists()
        rule.inDialog("Cancel").performClick()
        assertTrue(log.isEmpty())
        rule.onRow("Import another file").performClick()
        rule.inDialog("Choose a file").performClick()
        assertEquals(listOf("import ${BrainModel.OWN}"), log)
    }

    @Test fun aVoiceModelPageDeletesAfterAsking() {
        page(ModelRoutes.voice(VoiceBundle.STT))
        rule.onRow("Delete").performClick()
        rule.inDialog("typing only", substring = true).assertExists()
        rule.inDialog("Delete").performClick()
        assertEquals(listOf("voiceDelete ${VoiceBundle.STT}"), log)
    }

    /** Fix 1.1: speech recognition and Kokoro's pages say what 1.1 does in French (heard, and read by Siwis). */
    @Test fun speechRecognitionPage() {
        page(ModelRoutes.voice(VoiceBundle.STT))
        rule.onNodeWithText("on this phone, in English or French.", substring = true).assertExists()
        rule.shot("model_voice_stt")
    }

    @Test fun kokoroPage() {
        page(ModelRoutes.voice(VoiceBundle.KOKORO))
        rule.onNodeWithText("Siwis reads French replies when Robot's voice is Heart or Siwis.", substring = true).assertExists()
        rule.shot("model_voice_kokoro")
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun speechRecognitionPageInFrench() {
        page(ModelRoutes.voice(VoiceBundle.STT))
        rule.onNodeWithText("sur ce téléphone, en anglais ou en français.", substring = true).assertExists()
        rule.shot("model_voice_stt_fr")
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun kokoroPageInFrench() {
        page(ModelRoutes.voice(VoiceBundle.KOKORO))
        rule.onNodeWithText("Siwis lit les réponses en français quand la voix de Robot est Heart ou Siwis.", substring = true).assertExists()
        rule.shot("model_voice_kokoro_fr")
    }

    @Test fun anUnknownModelGoesBack() {
        page("model/nope")
        rule.waitForIdle()
        assertEquals(listOf("back"), log)
    }

    @Test fun aPixel10ListsItsTpuFastOnly() {
        tab()
        rule.onRow("Fast").assert(hasText("Gemma 4 E2B (Tensor G5) · Ready"))
        rule.onAllNodes(hasText("Fast") and hasClickAction()).assertCountEquals(1)
        rule.scrollToText("Pixel 10 Pro (Tensor G5)")
        rule.onNodeWithText("Pixel 10 Pro (Tensor G5)").assert(hasText("Its TPU is supported", substring = true))
        rule.onNodeWithText("Use the GPU").assertDoesNotExist()
    }

    @Test fun aPixel9aRunsTheGeneralFastOnTheGpuAndCanTurnItOff() {
        val host = tab(snap = nineA)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Fast").assert(hasText("Gemma 4 E2B · Ready")).assertIsSelected()
        rule.onAllNodes(hasText("Fast") and hasClickAction()).assertCountEquals(1)
        // An E4B model on an 8 GB phone, and the own TPU build that can't run here.
        rule.onRow("Smarter, slower").assert(hasText("Tight on this phone's memory", substring = true))
        rule.onRow(BrainModel.OWN.title).assertIsNotEnabled().assert(hasText("which this phone doesn't have", substring = true))
        rule.scrollToText("Pixel 9a (Tensor G4)")
        rule.onNodeWithText("Pixel 9a (Tensor G4)").assert(hasText("Google publishes no TPU build of Gemma for the Tensor G4", substring = true))
        rule.scrollToText("Fast on the GPU (now)")
        rule.scrollToText("Fast on the CPU")
        rule.scrollToText("Use the GPU")
        rule.onRow("Use the GPU").assertIsOn().performClick()
        assertEquals(false, host.last.modelGpu)
        rule.scrollToText("Fast on the CPU (now)")
        rule.onNodeWithText("Try the GPU again").assertDoesNotExist()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun aGpuFailureRunsFastOnTheCpuUntilYouTryTheGpuAgain() {
        tab(snap = nineA.copy(gpuFailure = GpuFailure(BrainModel.GENERAL, 1_760_000_000_000L, ProblemStage.LOADING, LoadFailure.ENGINE)))
        rule.scrollToText("The GPU didn't load (engine error)")
        rule.onNodeWithText("The GPU didn't load (engine error)").assert(hasText("Robot runs Fast on the CPU.", substring = true))
        rule.scrollToText("Fast on the CPU (now)")
        rule.scrollToText("Try the GPU again")
        rule.onRow("Try the GPU again").performClick()
        assertEquals(listOf("tryGpuAgain"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /** 1.2 (design Step 2): the GPU failed Robot's self-test: the row says what the check found; Try the GPU again checks again. */
    private val gpuCheckFailed = GpuFailure(BrainModel.GENERAL, 1_760_000_000_000L, ProblemStage.WRITING, null, "robolectric",
        app.heyrobot.core.GpuVerdict.GARBAGE)

    @Test fun aFailedGpuCheckSaysWhatItFound() {
        tab(snap = nineA.copy(gpuFailure = gpuCheckFailed))
        rule.scrollToText("The GPU failed Robot's check (garbled answer)")
        rule.onNodeWithText("The GPU failed Robot's check (garbled answer)").assert(hasText("Robot runs Fast on the CPU.", substring = true))
        rule.scrollToText("Try the GPU again")
        rule.shot("model_9a_check_failed")
        rule.onRow("Try the GPU again").performClick()
        assertEquals(listOf("tryGpuAgain"), log)
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun aFailedGpuCheckInFrench() {
        tab(snap = nineA.copy(gpuFailure = gpuCheckFailed))
        rule.scrollToText("Le GPU a échoué à la vérification de Robot (réponse brouillée)")
        rule.scrollToText("Réessayer le GPU")
        rule.shot("model_9a_check_failed_fr")
    }

    /** 1.2 (design Step 8): the setup guide's warning in the phone group, where Fast runs on the CPU; never on the GPU. */
    @Test fun aSlowPhoneSaysSoInThePhoneGroup() {
        tab(snap = nineA.copy(gpuFailure = gpuCheckFailed))
        rule.scrollToText("Robot will be slow on this phone")
        rule.onNodeWithText("Robot will be slow on this phone").assert(hasText("Robot's model runs on this phone's CPU. Estimate: a new " +
            "conversation starts answering in about ", substring = true))
        rule.onNodeWithText("Robot will be slow on this phone").assert(hasText("the model looks at the picture first.", substring = true))
    }

    @Test fun noSlowWarningOnTheGpu() {
        tab(snap = nineA)
        rule.scrollToText("Fast on the CPU")
        rule.onNodeWithText("Robot will be slow on this phone").assertDoesNotExist()
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun aSlowPhoneSaysSoInFrench() {
        tab(snap = nineA.copy(gpuFailure = gpuCheckFailed))
        rule.scrollToText("Robot sera lent sur ce téléphone")
        rule.onNodeWithText("Robot sera lent sur ce téléphone").assert(hasText("Le modèle de Robot fonctionne sur le CPU de ce téléphone. " +
            "Estimation : une nouvelle conversation", substring = true))
    }

    @Test fun theLastModelProblemHasItsReportToReadCopyOrShare() {
        tab(snap = nineA.copy(problem = problem))
        rule.onNodeWithText("Model problem").assertExists()
        rule.onNodeWithText(problem.headline()).assertExists()
        rule.onNodeWithText("Never what you said or asked", substring = true).assertExists()
        rule.onNodeWithText("REPORT for e2b").assertDoesNotExist()
        rule.onRow("Show the report").performClick()
        rule.onNodeWithText("REPORT for e2b").assertExists()
        rule.onRow("Copy report").performClick()
        rule.onRow("Share report").performClick()
        rule.onRow("Dismiss").performClick()
        assertEquals(listOf("copyReport REPORT for e2b", "shareReport REPORT for e2b", "dismissProblem ${problem.time}"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /** 0.7: the open problem goes to Send feedback as a bug report, its report ticked. */
    @Test fun theLastModelProblemSendsAsABugReport() {
        tab(snap = nineA.copy(problem = problem, problems = listOf(problem)))
        rule.onRow("Send as bug report").performClick()
        assertEquals(listOf("openPage ${Page.Settings("about/feedback?e=problem&k=bug&p=${problem.time}")}"), log)
        // The open problem isn't listed again under it.
        rule.onNodeWithText("Last model problems").assertDoesNotExist()
    }

    /**
     * 0.7: the last 10 problems as rows (date, process and stage, codes only), each opening Show, Share, Send as bug report
     * and Delete; with no open problem the list sits at the end of the tab.
     */
    @Test fun theLastProblemsAreAListToShowShareSendOrDelete() {
        val older = ModelProblem(problem.time - 86_400_000L, BrainModel.OWN, ModelBackend.CPU, ProblemStage.LOADING, null, BrainModel.GENERAL, ModelBackend.GPU,
            format = app.heyrobot.core.ModelFormat.GGUF)
        tab(snap = nineA.copy(problem = problem, problems = listOf(problem, older)))
        rule.onNodeWithText("Last model problems").assertExists()
        rule.onRow(":llama · loading · Your own model file on the CPU").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        rule.onRow(":llama · loading · Your own model file on the CPU").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        rule.scrollToText("Delete")
        rule.onNodeWithText("REPORT for own").assertDoesNotExist()
        rule.onRow("Show").performClick()
        rule.onNodeWithText("REPORT for own").assertExists()
        rule.onRow("Share").performClick()
        rule.onAllNodes(hasText("Send as bug report") and hasClickAction())[1].performClick()
        rule.onRow("Delete").performClick()
        assertEquals(listOf("shareReport REPORT for own", "openPage ${Page.Settings("about/feedback?e=problem&k=bug&p=${older.time}")}",
            "deleteProblem ${older.time}"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun aDismissedProblemStaysInTheListAtTheEnd() {
        tab(snap = nineA.copy(problem = null, problems = listOf(problem)))
        rule.onNodeWithText("Model problem").assertDoesNotExist()
        rule.scrollToText("Last model problems")
        rule.onRow(":brain · writing the answer · Fast on the GPU").assertExists()
    }

    @Test fun theGeneralFastsPageSaysItsCachesAndThisPhonesSpeeds() {
        page(ModelRoutes.model(BrainModel.GENERAL), snap = nineA)
        rule.onNodeWithText("On this phone, on the GPU.", substring = true).assertExists()
        rule.scrollToText("on the GPU, up to", substring = true)
    }

    @Test fun anE4bModelsPageWarnsAboutThisPhonesMemory() {
        page(ModelRoutes.model(BrainModel.SMARTER), snap = nineA)
        rule.onNodeWithText("This phone's memory").assert(hasText("Android may stop this model", substring = true))
    }

    /**
     * 0.8: a Pixel 11 (Tensor G6): Fast is the Tensor G6 build, marked beta, its speed an estimate from the Tensor G5's;
     * your own Tensor G5 build can't run on its TPU; once the beta failed, Fast is the general build and the TPU can be
     * tried again.
     */
    private val eleven = snap.copy(
        phone = PIXEL_11,
        models = listOf(
            ModelStore.Snap(BrainModel.FAST_G6, ModelStatus.MISSING),
            ModelStore.Snap(BrainModel.GENERAL, ModelStatus.MISSING),
            ModelStore.Snap(BrainModel.SMARTER, ModelStatus.MISSING),
            ModelStore.Snap(BrainModel.UNCENSORED, ModelStatus.MISSING),
            ModelStore.Snap(BrainModel.OWN, ModelStatus.VERIFIED, fileBytes = tpuBuild.bytes),
        ),
    )

    @Test fun aPixel11ListsItsTensorG6FastAsABeta() {
        tab(snap = eleven)
        rule.onRow("Fast (beta)").assertIsSelected().assert(hasText("Gemma 4 E2B (Tensor G6) · Not downloaded", substring = true))
        // Its fallback, the general build, listed to download before the TPU build ever fails: a row to its page, not a choice.
        rule.onAllNodes(hasText("Fast", substring = true) and hasClickAction()).assertCountEquals(2)
        rule.onRow("Fast (general build, fallback)").assert(hasText("Used by itself if Fast (beta) doesn't load on the TPU", substring = true))
            .assert(hasText("Not downloaded", substring = true)).performClick()
        assertEquals("openPage ${Page.Settings(ModelRoutes.model(BrainModel.GENERAL))}", log.last())
        // Your own Tensor G5 build: not on a Tensor G6's TPU.
        rule.onRow(BrainModel.OWN.title).assertIsNotEnabled().assert(hasText("Built for the Tensor G5's TPU, which this phone doesn't have", substring = true))
        rule.scrollToText("Pixel 11 (Tensor G6)")
        rule.onNodeWithText("Pixel 11 (Tensor G6)").assert(hasText("supported in beta", substring = true))
        rule.onNodeWithText("Try the TPU again").assertDoesNotExist()
    }

    @Test fun theFallbacksPageDownloadsTheGeneralBuild() {
        page(ModelRoutes.model(BrainModel.GENERAL), snap = eleven)
        rule.onNodeWithText("Fast (general build, fallback)").assertExists()
        rule.onRow("Download ${ModelLogic.gb(BrainModel.GENERAL.bytes)}").performClick()
        assertEquals(listOf("download ${BrainModel.GENERAL}"), log)
    }

    @Test fun theTensorG6PageSaysItsSpeedIsAnEstimate() {
        page(ModelRoutes.model(BrainModel.FAST_G6), snap = eleven)
        rule.onNodeWithText("Estimate:", substring = true).assertExists()
        rule.onNodeWithText("Estimate, not measured", substring = true).assertExists()
    }

    /** 1.2: the Tensor G6's beta TPU build failed Robot's self-test: the general build, and what the check found. */
    private fun tpuCheckFailed() = eleven.copy(tpuFailure = app.heyrobot.core.TpuFailure(BrainModel.FAST_G6, 1_760_000_000_000L, null, "robolectric",
        app.heyrobot.core.GpuVerdict.GARBAGE),
        models = listOf(ModelStore.Snap(BrainModel.GENERAL, ModelStatus.VERIFIED, fileBytes = BrainModel.GENERAL.bytes)) +
            eleven.models.filter { it.model != BrainModel.FAST_G6 && it.model != BrainModel.GENERAL })

    @Test fun aFailedTpuCheckSaysWhatItFound() {
        tab(snap = tpuCheckFailed())
        rule.scrollToText("The TPU failed Robot's check (garbled answer)")
        rule.onNodeWithText("The TPU failed Robot's check (garbled answer)").assert(hasText("general build on the CPU", substring = true))
        rule.scrollToText("Try the TPU again")
        rule.shot("model_11_check_failed")
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun aFailedTpuCheckInFrench() {
        tab(snap = tpuCheckFailed())
        rule.scrollToText("Le TPU a échoué à la vérification de Robot (réponse brouillée)")
        rule.scrollToText("Réessayer le TPU")
        rule.shot("model_11_check_failed_fr")
    }

    @Test fun aTpuFailureRunsTheGeneralFastUntilYouTryTheTpuAgain() {
        val failed = eleven.copy(tpuFailure = app.heyrobot.core.TpuFailure(BrainModel.FAST_G6, 1_760_000_000_000L, LoadFailure.ENGINE),
            models = listOf(ModelStore.Snap(BrainModel.GENERAL, ModelStatus.VERIFIED, fileBytes = BrainModel.GENERAL.bytes)) +
                eleven.models.filter { it.model != BrainModel.FAST_G6 && it.model != BrainModel.GENERAL })
        tab(snap = failed)
        rule.onRow("Fast").assertIsSelected().assert(hasText("Gemma 4 E2B · Ready"))
        rule.onNodeWithText("Fast (beta)").assertDoesNotExist()
        rule.onNodeWithText("Fast (general build, fallback)").assertDoesNotExist()
        rule.scrollToText("The TPU didn't load (engine error)")
        rule.onNodeWithText("The TPU didn't load (engine error)").assert(hasText("general build on the CPU", substring = true))
        rule.scrollToText("Try the TPU again")
        rule.onRow("Try the TPU again").performClick()
        assertEquals(listOf("tryTpuAgain"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun yourOwnTpuBuildCantRunOnAPixel9a() {
        page(ModelRoutes.model(BrainModel.OWN), snap = nineA)
        rule.scrollToText("which this phone doesn't have", substring = true)
        rule.onNodeWithText("Run on the TPU").assertDoesNotExist()
    }

    /** 1.2: a Galaxy S25: the general E2B, nothing of yours imported. */
    private val s25 = nineA.copy(phone = GALAXY_S25, own = null,
        models = nineA.models.map { if (it.model == BrainModel.OWN) ModelStore.Snap(BrainModel.OWN, ModelStatus.MISSING) else it })

    /** 1.2: this phone's chip as Android names it, on every phone: a Pixel's own fields, and "not given" for an empty one. */
    @Test fun everyPhoneShowsItsSocFields() {
        tab()
        rule.scrollToText("Chip, as Android names it")
        rule.onNodeWithText("Chip, as Android names it").assertExists()
        rule.onNodeWithText("Google · Tensor G5").assertExists()
        rule.onNodeWithText("Pixel 10 Pro (Tensor G5)").assertExists()
    }

    @Test fun aPhoneWithoutSocFieldsSaysSo() {
        tab(snap = nineA.copy(phone = PIXEL_9A.copy(socManufacturer = "", socModel = "")))
        rule.scrollToText("Chip, as Android names it")
        rule.onNodeWithText("Chip, as Android names it").assertExists()
        rule.onNodeWithText("not given · not given").assertExists()
        // A Pixel 9a by its name alone: its chip as before.
        rule.onNodeWithText("Pixel 9a (Tensor G4)").assertExists()
    }

    /**
     * 1.2: a Galaxy S25 (Snapdragon 8 Elite) names its chip, says it has no NPU build (a Snapdragon's accelerator is its
     * NPU), shows Android's SoC fields, and runs Fast on the GPU or the CPU as any phone without a TPU build.
     */
    @Test fun aGalaxyS25NamesItsChip() {
        tab(snap = s25)
        rule.onRow("Fast").assertIsSelected().assert(hasText("Gemma 4 E2B · Ready"))
        rule.scrollToText("Chip, as Android names it")
        rule.onNodeWithText("Samsung SM-S931W (Snapdragon 8 Elite)").assertExists()
        rule.onNodeWithText("Robot has no NPU build for this phone's Snapdragon 8 Elite: Fast is Gemma 4 E2B's general build, on the GPU " +
            "(recommended) or the CPU.").assertExists()
        rule.onNodeWithText("Chip, as Android names it").assertExists()
        rule.onNodeWithText("QTI · SM8750").assertExists()
        rule.shot("model_s25")
        rule.scrollToText("Use the GPU")
        rule.onRow("Use the GPU").assertIsOn()
        rule.scrollToText("No figure for this phone's GPU", substring = true)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun aGalaxyS25InFrench() {
        tab(snap = s25)
        rule.scrollToText("Puce, telle qu’Android la nomme")
        rule.onNodeWithText("Samsung SM-S931W (Snapdragon 8 Elite)").assertExists()
        rule.onNodeWithText("Robot n’a aucune version NPU pour le Snapdragon 8 Elite de ce téléphone\u00A0: Rapide est la version générale",
            substring = true).assertExists()
        rule.onNodeWithText("Puce, telle qu’Android la nomme").assertExists()
        rule.onNodeWithText("QTI · SM8750").assertExists()
        rule.shot("model_s25_fr")
        rule.scrollToText("Utiliser le GPU")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /** 1.2: three answers Robot measured on this Galaxy S25's GPU, on its Android build (core SpeedLog). */
    private val s25Build = "samsung/pa1qxx/pa1q:16/BP2A.250605.031.A3/S931WVLS4AYI1:user/release-keys"
    private fun measured(m: BrainModel, b: ModelBackend, sha: String?, build: String, vararg rw: Pair<Double, Double>) =
        rw.map { (r, w) -> SpeedKey.of(m, b, sha, build)!! to SpeedSample(1500, r, 90, w, 3500, true) }
    private val s25Measured = s25.copy(build = s25Build,
        speeds = measured(BrainModel.GENERAL, ModelBackend.GPU, BrainModel.GENERAL.sha256, s25Build, 400.0 to 12.0, 410.0 to 12.5, 420.0 to 13.0))

    /**
     * 1.2 (design Step 5): once Robot has measured three answers on this phone's GPU, Fast on the GPU shows those figures
     * (the medians: 410 tokens a second read, 12.5 written) instead of the Tensor G4's estimate; the CPU, not measured yet,
     * keeps its estimate and where it comes from.
     */
    @Test fun aGalaxyS25ShowsWhatItMeasured() {
        tab(snap = s25Measured)
        rule.scrollToText("Fast on the CPU")
        rule.onNodeWithText("Measured on this phone: a new conversation starts answering in about 4 s, later answers in about 1 s, then about 9 " +
            "words a second.").assertExists()
        rule.onNodeWithText("Robot's medians over your last 3 answers on this phone: 410 tokens a second read, 12.5 written. These numbers stay " +
            "on this phone.").assertExists()
        rule.onNodeWithText("No figure for this phone's GPU", substring = true).assertDoesNotExist()
        rule.shot("model_s25_measured")
        rule.scrollToText("Estimated for a Tensor G4 as two thirds", substring = true)
        rule.onNodeWithText("Estimate: a new conversation starts answering in about", substring = true).assertExists()
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun aGalaxyS25ShowsWhatItMeasuredInFrench() {
        tab(snap = s25Measured)
        rule.scrollToText("Rapide sur le CPU")
        rule.onNodeWithText("Mesuré sur ce téléphone\u00A0: une nouvelle conversation commence à répondre en environ", substring = true).assertExists()
        rule.onNodeWithText("puis environ 9 mots par seconde.", substring = true).assertExists()
        rule.onNodeWithText("Médianes mesurées par Robot sur tes 3 dernières réponses sur ce téléphone\u00A0: 410 jetons par seconde lus, 12,5 écrits. " +
            "Ces chiffres restent sur ce téléphone.").assertExists()
        rule.shot("model_s25_measured_fr")
    }

    /** 1.2: an OS update (another Android build) and the estimate is back until Robot has measured the new build. */
    @Test fun anOsUpdateShowsTheEstimateAgain() {
        tab(snap = s25Measured.copy(build = "samsung/pa1qxx/pa1q:16/BP2A.250805.005/S931WVLS5AYJ2:user/release-keys"))
        rule.scrollToText("No figure for this phone's GPU", substring = true)
        rule.onNodeWithText("Measured on this phone", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Robot's medians", substring = true).assertDoesNotExist()
    }

    /** 1.2: two answers aren't enough to call it measured. */
    @Test fun twoAnswersShowTheEstimate() {
        tab(snap = s25Measured.copy(speeds = s25Measured.speeds.take(2)))
        rule.scrollToText("No figure for this phone's GPU", substring = true)
        rule.onNodeWithText("Measured on this phone", substring = true).assertDoesNotExist()
    }

    private val blazer = "google/blazer/blazer:16/BP3A.251005.004/14112847:user/release-keys"

    /** 1.2: a Pixel shows the published figures until Robot has measured it. */
    @Test fun aPixelKeepsThePublishedFiguresUntilMeasured() {
        page(ModelRoutes.model(BrainModel.FAST), snap = snap.copy(build = blazer))
        rule.onNodeWithText("Writes about 17 tokens", substring = true).assertExists()
        rule.onNodeWithText("Measured on this phone", substring = true).assertDoesNotExist()
    }

    /** 1.2: Fast on a Tensor G5 then says what Robot measured on this phone's TPU, beside its published line. */
    @Test fun aTensorG5ShowsWhatItMeasured() {
        val fast = measured(BrainModel.FAST, ModelBackend.TPU, BrainModel.FAST.sha256, blazer, 520.0 to 17.0, 530.0 to 17.2, 500.0 to 16.8)
        page(ModelRoutes.model(BrainModel.FAST), snap = snap.copy(build = blazer, speeds = fast))
        rule.onNodeWithText("Writes about 17 tokens", substring = true).assertExists()
        // 1.2, job 5: the first word is the measured one (3.5 s in these answers, so "about 4 s"), no longer 1,500 tokens at 520 a second.
        rule.onNodeWithText("On the TPU. Measured on this phone: a new conversation starts answering in about 4 s", substring = true).assertExists()
        rule.onNodeWithText("Robot's medians over your last 3 answers on this phone: 520 tokens a second read, 17 written.", substring = true).assertExists()
    }

    private val ownOnTpu = tpuBuild.copy(tpuAllowed = true)

    /** 1.2: your own file has no figure of Robot's until it measures it here. */
    @Test fun yourOwnFileHasNoFigureUntilMeasured() {
        page(ModelRoutes.model(BrainModel.OWN), snap = snap.copy(build = blazer, own = ownOnTpu))
        rule.onNodeWithText("Measured on this phone", substring = true).assertDoesNotExist()
    }

    @Test fun yourOwnFileShowsWhatRobotMeasured() {
        page(ModelRoutes.model(BrainModel.OWN), snap = snap.copy(build = blazer, own = ownOnTpu, speeds = measured(BrainModel.OWN, ModelBackend.TPU,
            ownOnTpu.sha256, blazer, 300.0 to 10.0, 310.0 to 11.0, 320.0 to 12.0, 330.0 to 13.0)))
        rule.scrollToText("Measured on this phone", substring = true)
        rule.onNodeWithText("Robot's medians over your last 4 answers on this phone: 315 tokens a second read, 11.5 written.", substring = true).assertExists()
    }

    @Test fun light() {
        tab()
        rule.shot("model_light")
    }

    /** 0.4.7: a Pixel 9a with a model problem at the top. */
    @Test fun pixel9aWithAProblem() {
        // The problem's time in the same zone on every computer the goldens are checked on.
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            tab(snap = nineA.copy(problem = problem))
            rule.shot("model_9a_problem")
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    /**
     * 1.1: French menus (values-fr-rCA/strings_model.xml and core's ModelStrings): the tab, its rows' states and sizes, this
     * phone's TPU, the memory and voice models' rows and the footer in Québec French; Fast keeps its name.
     */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun inFrench() {
        tab()
        rule.onNodeWithText("Robot utilise").assertExists()
        rule.onRow("Rapide").assert(hasText("Gemma 4 E2B (Tensor G5) · Prêt"))
        rule.onRow("Plus futé, plus lent").assert(hasText("Gemma 4 E4B (CPU) · Pas téléchargé · 3,66\u00A0Go"))
        rule.onNodeWithText("Son TPU est pris en charge\u00A0: Rapide est la version", substring = true).assertExists()
        rule.shot("model_fr")
        rule.scrollToText("Garder le modèle chargé")
        rule.onRow("Garder le modèle chargé").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "5\u00A0min après la fermeture du panneau"))
        rule.scrollToText("Reconnaissance vocale")
        rule.onRow("Reconnaissance vocale").assert(hasText("Prêt · 670\u00A0Mo"))
        rule.scrollToText("Les modèles fonctionnent sur ce téléphone.", substring = true)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /** 1.1: a model problem, the GPU's failure and Fast's speeds on a Pixel 9a, with French menus. */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun aProblemAndTheGpuInFrench() {
        tab(snap = nineA.copy(problem = problem, gpuFailure = GpuFailure(BrainModel.GENERAL, 1_760_000_000_000L, ProblemStage.LOADING, LoadFailure.ENGINE)))
        rule.onNodeWithText("Rapide s\u2019est arrêté sur le GPU, alors Robot est passé au CPU.").assertExists()
        rule.onNodeWithText("Copier le rapport").assertExists()
        rule.scrollToText("Le GPU ne s\u2019est pas chargé (erreur du moteur)")
        rule.scrollToText("Réessayer le GPU")
        rule.scrollToText("Estimation\u00A0: une nouvelle conversation commence à répondre", substring = true)
    }

    /** 1.1: a model's page and its publisher's conditions, with French menus. */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun aModelsPageInFrench() {
        page(ModelRoutes.model(BrainModel.UNCENSORED))
        rule.onNodeWithText("Pas téléchargé").assertExists()
        rule.onNodeWithText("Télécharger 3,66\u00A0Go").performClick()
        rule.inDialog("Avant de télécharger «\u00A0Sans censure, plus lent\u00A0»").assertExists()
        rule.inDialog("Confirmer et télécharger").assertExists()
    }
}
