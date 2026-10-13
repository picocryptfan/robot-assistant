package app.heyrobot.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import app.heyrobot.core.LookSettings
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.WakeAction
import app.heyrobot.core.feedback.Diagnostic
import app.heyrobot.core.feedback.FeedbackDiagnostics
import app.heyrobot.core.feedback.FeedbackDraft
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackFacts
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.feedback.FeedbackReport
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * 0.7, Settings › About › Send feedback: what each kind asks and ticks, Preview showing exactly what goes, Share, Copy,
 * Text it and the GitHub issue reaching the app, Done clearing the draft, the panel's words, the draft surviving a
 * recreation, TalkBack and touch targets in both looks, and its screenshots (light, dark, text at 200 %).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class FeedbackScreenTest {
    @get:Rule val rule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val t = 1_760_000_000_000L
    private val facts = FeedbackFacts("0.7.0 (70) · release build", "Pixel 9a · Tensor G4 · Android 16 (BP2A.250805.005) · GrapheneOS",
        "Fast · Gemma 4 E2B · GPU", "stock", "en-CA")
    private val blocks = listOf(
        FeedbackDiagnostics.problem("Robot problem report\nFast stopped on the GPU, so Robot switched to the CPU."),
        FeedbackDiagnostics.voiceLog(List(3) { VoiceLogEntry(t - it * 1_000L, VoiceEvent.WAKE, 2, -1, WakeAction.OPENED.id) }, utc),
        FeedbackDiagnostics.settings(RobotSettings(), LookSettings(), UpdateSettings()),
    )
    private val ui = FeedbackUi(facts, blocks, problemDate = "9 Oct 2025, 08:53")
    private val about = FeedbackArgs(FeedbackEntry.ABOUT, null, null)
    private val repo = GitHubRepo("picocryptfan", "robot-assistant")

    /** What reached the app: "share BUG 7 …text…" and the like. */
    private val taps = ArrayList<String>()
    private val outs = ArrayList<FeedbackOut>()
    private var backs = 0
    private val actions = FeedbackActions(
        share = { outs += it; taps += "share ${it.kind} ${it.bits}"; true },
        copy = { outs += it; taps += "copy ${it.kind} ${it.bits}" },
        textIt = { outs += it; taps += "text ${it.kind} ${it.bits}"; true },
        github = { k, link -> taps += "github $k ${link.url.substringBefore("&body=")}"; true },
        saveNumber = { taps += "number $it" },
    )

    private fun page(args: FeedbackArgs = about, u: FeedbackUi? = ui, prefill: String? = null, classic: Boolean = false, dark: Boolean = false) =
        rule.show(dark = dark, classic = classic) { FeedbackScreen(args, u, actions, onBack = { backs++ }, prefill = prefill, newRef = { "RFB-ABCD2345" }) }

    private fun box(title: String) = rule.onNode(hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))

    private fun scrollTo(text: String) {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        rule.waitForIdle()
    }

    @Test fun aBugTicksItsDiagnosticsAndAnIdeaNone() {
        page()
        rule.onNodeWithText("Bug").assertIsSelected()
        rule.onNode(hasText("What happened?") and hasSetTextAction()).assertExists()
        rule.onNode(hasText("Steps (optional)") and hasSetTextAction()).assertExists()
        scrollTo("Settings summary")
        box("Problem report").assertIsOn().assert(hasText("The model problem of 9 Oct 2025, 08:53", substring = true))
        box("Voice log").assertIsOn()
        box("Settings summary").assertIsOn()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Idea"))
        rule.onNodeWithText("Idea").performClick()
        rule.onNode(hasText("What would you like Robot to do?") and hasSetTextAction()).assertExists()
        rule.onNode(hasText("Steps (optional)") and hasSetTextAction()).assertDoesNotExist()
        scrollTo("Settings summary")
        for (b in listOf("Problem report", "Voice log", "Settings summary")) box(b).assertIsOff()
        // You may tick one yourself; Other asks its own question.
        box("Voice log").performClick()
        box("Voice log").assertIsOn()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Other"))
        rule.onNodeWithText("Other").performClick()
        rule.onNode(hasText("What would you like to tell the developer?") and hasSetTextAction()).assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun previewShowsExactlyWhatGoes() {
        page()
        scrollTo("Settings summary")
        rule.onNode(hasContentDescription("Preview the voice log")).performClick()
        val expected = FeedbackReport.compose(FeedbackDraft(FeedbackKind.BUG, "", ticked = Diagnostic.entries.toSet()), "RFB-ABCD2345", facts, blocks)
            .blocks.getValue(Diagnostic.VOICE_LOG)
        rule.onNode(hasText(expected) and hasAnyAncestor(isDialog())).assertExists()
        assertTrue(expected, expected.startsWith("• Voice log, last 3 events") && expected.contains("WAKE 2 -1 0  Wake word #2: opened the panel"))
        rule.inDialog("Close").performClick()
        // An unticked one previews what ticking it would add.
        box("Settings summary").performClick()
        rule.onNode(hasContentDescription("Preview the settings summary")).performClick()
        rule.onNode(hasText("Settings summary (switches and levels only):", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        rule.inDialog("Close").performClick()
        // The whole message, exactly as Share hands it over.
        scrollTo("Preview the whole message")
        rule.onRow("Preview the whole message").performClick()
        val whole = FeedbackReport.compose(FeedbackDraft(FeedbackKind.BUG, "", ticked = setOf(Diagnostic.PROBLEM, Diagnostic.VOICE_LOG)), "RFB-ABCD2345",
            facts, blocks).text
        rule.onNode(hasText(whole) and hasAnyAncestor(isDialog())).assertExists()
        rule.inDialog("Close").performClick()
        scrollTo("Share")
        rule.onNodeWithText("Share").performClick()
        assertEquals(whole, outs.single().text)
    }

    // Typing runs on Robolectric's 320 dp screen, as Settings' other tests that type do.
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun shareSendsYourWordsAndDoneClearsTheDraft() {
        page()
        rule.onNode(hasText("What happened?") and hasSetTextAction()).performTextReplacement("The timer didn't ring.")
        rule.onNode(hasText("What you expected (optional)") and hasSetTextAction()).performTextReplacement("A ring.")
        scrollTo("Share")
        rule.onNodeWithText("Sent it?").assertDoesNotExist()
        rule.onNodeWithText("Share").performClick()
        assertEquals(listOf("share BUG 7"), taps)
        val text = outs.single().text
        assertTrue(text, text.startsWith("ROBOT FEEDBACK v1\nKind: Bug report\nRef: RFB-ABCD2345\n") &&
            text.contains("What happened:\nThe timer didn't ring.\n---\nSteps:\n(none written)\nExpected:\nA ring.\n") &&
            text.contains("• Last problem report:\nRobot problem report\n") && text.endsWith(FeedbackReport.FOOTER + "\n"))
        rule.onNodeWithText("Copy").performClick()
        assertEquals("copy BUG 7", taps.last())
        scrollTo("Sent it?")
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, backs)
        rule.onNode(hasText("The timer didn't ring.")).assertDoesNotExist()
    }

    @Test fun textItAndGitHubOnlyWhenSet() {
        page()
        scrollTo("Share")
        rule.onNodeWithText("Text it").assertDoesNotExist()
        rule.onNodeWithText("Open a GitHub issue").assertDoesNotExist()
    }

    @Test fun textItAndTheGitHubIssue() {
        page(u = ui.copy(sendTo = "+15145550123", github = repo))
        scrollTo("Open a GitHub issue")
        rule.onNodeWithText("Text it opens your SMS app with +15145550123 filled in", substring = true).assertExists()
        rule.onNodeWithText("A private repository needs you signed in to GitHub there.", substring = true).assertExists()
        rule.onNodeWithText("Text it").performClick()
        rule.onNodeWithText("Open a GitHub issue").performClick()
        assertEquals(listOf("text BUG 7", "github BUG https://github.com/picocryptfan/robot-assistant/issues/new?title=Bug%20report%3A%20RFB-ABCD2345"), taps)
        rule.onNodeWithText("Sent it?").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theNumberToTextIsInTheMenu() {
        var u by mutableStateOf(ui)
        rule.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) {
            FeedbackScreen(about, u, FeedbackActions(saveNumber = { n -> taps += "number $n"; u = u.copy(sendTo = n) }), onBack = {})
        } }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Send to…").performClick()
        val field = rule.onNode(hasText("Phone number") and hasSetTextAction())
        field.performTextReplacement("mom")
        rule.onNodeWithText("Not a phone number").assertExists()
        rule.inDialog("Save").assertIsNotEnabled()
        field.performTextReplacement("+1 (514) 555-0123")
        rule.inDialog("Save").assertIsEnabled().performClick()
        assertEquals(listOf("number +15145550123"), taps)
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Send to: +15145550123").assertExists()
        rule.onNodeWithText("Remove the number").performClick()
        assertEquals("number null", taps.last())
    }

    @Test fun thePanelsWordsStartYourText() {
        page(FeedbackArgs(FeedbackEntry.VOICE, FeedbackKind.IDEA, null), prefill = "Read my calendar in the brief")
        rule.onNodeWithText("Idea").assertIsSelected()
        rule.onNode(hasText("What would you like Robot to do?") and hasText("Read my calendar in the brief", substring = true)).assertExists()
        scrollTo("Settings summary")
        box("Voice log").assertIsOff()
    }

    @Test fun aModelProblemOpensAsABugWithItsReport() {
        page(FeedbackArgs(FeedbackEntry.MODEL_PROBLEM, FeedbackKind.BUG, t))
        rule.onNodeWithText("Bug").assertIsSelected()
        scrollTo("Problem report")
        box("Problem report").assertIsOn()
    }

    /** While Robot reads its codes, you can already write; the buttons wait for them. */
    @Test fun whileReadingTheButtonsWait() {
        page(u = null)
        rule.onNodeWithText("Reading Robot's codes…").assertExists()
        scrollTo("Share")
        rule.onNodeWithText("Share").assertIsNotEnabled()
        rule.onNodeWithText("Copy").assertIsNotEnabled()
    }

    /** Your draft survives a rotation or Android stopping the app: the kind, the words, the ticks, the reference. */
    @Test fun theDraftSurvivesARecreation() {
        val restore = StateRestorationTester(rule)
        restore.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) { FeedbackScreen(about, ui, actions, onBack = {}) } }
        rule.onNodeWithText("Idea").performClick()
        rule.onNode(hasText("What would you like Robot to do?") and hasSetTextAction()).performTextReplacement("Read my calendar in the brief")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Voice log"))
        box("Voice log").performClick()
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Idea").assertIsSelected()
        rule.onNode(hasText("Read my calendar in the brief", substring = true) and hasSetTextAction()).assertExists()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Voice log"))
        box("Voice log").assertIsOn()
        box("Settings summary").assertIsOff()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Share"))
        rule.onNodeWithText("Share").performClick()
        assertTrue(outs.single().text.contains("Kind: Idea\nRef: RFB-") && outs.single().text.contains("The idea:\nRead my calendar in the brief\n"))
    }

    @Test fun theClassicLookIsTheSame() {
        // The Classic look changes the panel only (ui-stock-design 4): the page and its taps work alike.
        page(classic = true)
        scrollTo("Share")
        rule.onNodeWithText("Share").performClick()
        assertEquals(listOf("share BUG 7"), taps)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun aboutHasTheSendFeedbackRow() {
        val log = ActionLog()
        rule.show { AboutSettingsScreen(log.actions, onBack = {}) }
        rule.onRow("Send feedback").assert(hasText("A bug or an idea, to the developer: you choose the app it goes with")).performClick()
        assertEquals(listOf("openPage ${Page.Settings("about/feedback?e=about")}"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun light() {
        page()
        rule.shot("feedback_light")
    }

    @Test fun dark() {
        page(FeedbackArgs(FeedbackEntry.VOICE, FeedbackKind.IDEA, null), u = ui.copy(sendTo = "+15145550123", github = repo), prefill = "Read my calendar in the brief",
            dark = true)
        rule.shot("feedback_dark")
    }

    /** At twice the text size the page is long: on a screen tall enough for all of it, every target is whole and big enough. */
    @Test @Config(fontScale = 2f, qualifiers = "w411dp-h4200dp-mdpi")
    fun atTwiceTheTextSize() {
        page(u = ui.copy(sendTo = "+15145550123", github = repo))
        rule.onNodeWithText("Open a GitHub issue").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("feedback_font200")
    }
}
