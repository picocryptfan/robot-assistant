package app.heyrobot.ui.model

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.heyrobot.core.BrainModel
import app.heyrobot.core.LiteRtLmReport
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
    private val snap = Snapshot(
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

    private fun tab(start: RobotSettings = RobotSettings()): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> ModelScreen(s, u, snap, null, log.actions) }
        return host
    }

    private fun page(route: String, start: RobotSettings = RobotSettings()): SettingsHost {
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

    @Test fun light() {
        tab()
        rule.shot("model_light")
    }
}
