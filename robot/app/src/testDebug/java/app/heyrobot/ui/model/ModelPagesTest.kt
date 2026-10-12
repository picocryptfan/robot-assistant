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
import app.heyrobot.core.VoiceBundle
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.VoiceModelStore
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.settings.SettingsNav
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.PIXEL_10_PRO
import app.heyrobot.ui.testing.PIXEL_9A
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

/**
 * The Model tab (radio rows with a details button each, thinking, memory, the voice models) and its pages: a model's
 * Download and Import (with its publisher's terms first when it has them), your own file's TPU dialog, a voice model's Delete.
 * 0.4.7's parts: only this phone's Fast, this phone and its chip, "Use the GPU" and "Try the GPU again" on a Pixel 9a, the
 * last model problem's report, the memory warning, in both looks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ModelPagesTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val sha = "a".repeat(64)
    private val tpuBuild = OwnModel("gemma-own.litertlm", 3_000_000_000L, sha, "fp",
        LiteRtLmReport(readable = true, decoder = true, aux = true, dispatchOp = true, tensorG5Stamp = true, modelType = "gemma4"))
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

    private fun tab(start: RobotSettings = RobotSettings(), snap: Snapshot = this.snap, classic: Boolean = false): SettingsHost {
        val host = SettingsHost(start)
        rule.show(classic = classic) { ModelScreen(host.now, host::update, snap, null, log.actions) }
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

    @Test fun aVoiceModelPageDeletesAfterAsking() {
        page(ModelRoutes.voice(VoiceBundle.STT))
        rule.onRow("Delete").performClick()
        rule.inDialog("typing only", substring = true).assertExists()
        rule.inDialog("Delete").performClick()
        assertEquals(listOf("voiceDelete ${VoiceBundle.STT}"), log)
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

    @Test fun theModelTabIsTheSameInTheClassicLook() {
        // The Classic look changes the panel only (ui-stock-design 4): the Model tab, its problem and the GPU rows work alike.
        tab(snap = nineA.copy(problem = problem), classic = true)
        rule.onRow("Copy report").performClick()
        rule.scrollToText("Use the GPU")
        rule.onRow("Use the GPU").assertIsOn()
        assertEquals(listOf("copyReport REPORT for e2b"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
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

    @Test fun yourOwnTpuBuildCantRunOnAPixel9a() {
        page(ModelRoutes.model(BrainModel.OWN), snap = nineA)
        rule.scrollToText("which this phone doesn't have", substring = true)
        rule.onNodeWithText("Run on the TPU").assertDoesNotExist()
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
}
