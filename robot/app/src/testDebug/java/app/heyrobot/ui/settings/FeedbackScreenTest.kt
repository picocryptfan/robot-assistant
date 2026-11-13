// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.ui.test.assertCountEquals
import app.heyrobot.core.feedback.ReportHide
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import app.heyrobot.ui.testing.setShotContent
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.internal.TimeLimitedStatement
import java.time.ZoneId

/**
 * 0.7, Settings › About › Send feedback: what each kind asks and ticks, Preview showing exactly what goes, Share, Copy,
 * Text it and the GitHub issue reaching the app, Done clearing the draft, the panel's words, the draft surviving a
 * recreation, TalkBack and touch targets, and its screenshots (light, dark, text at 200 %).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class FeedbackScreenTest {
    // Outermost: a test that waits for a frame that never comes fails after a minute instead of stalling the suites
    // (9 Nov 2026: one hung for 47 minutes). Robolectric's own statement, on its main thread: JUnit's Timeout moves the
    // test to another thread, where the compose rule can't reach the main looper.
    @get:Rule(order = -2) val timeout = TestRule { base, _ -> TimeLimitedStatement(60_000, base) }
    @get:Rule val rule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val t = 1_760_000_000_000L
    private val facts = FeedbackFacts("0.7.0 (70) · release build", "Pixel 9a · Tensor G4 · Android 16 (BP2A.250805.005) · GrapheneOS",
        "Fast · Gemma 4 E2B · GPU", "stock", "en-CA", "Build.SOC_MANUFACTURER “Google” · Build.SOC_MODEL “Tensor G4”")
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
    private val links = ArrayList<String>()
    private val outs = ArrayList<FeedbackOut>()
    private var backs = 0
    private val actions = FeedbackActions(
        share = { outs += it; taps += "share ${it.kind} ${it.bits}"; true },
        copy = { outs += it; taps += "copy ${it.kind} ${it.bits}" },
        textIt = { outs += it; taps += "text ${it.kind} ${it.bits}"; true },
        github = { k, link -> links += java.net.URLDecoder.decode(link.url, "UTF-8"); taps += "github $k ${link.url.substringBefore("&template=")}"; true },
        saveNumber = { taps += "number $it" },
        githubCard = { o, url -> outs += o; links += java.net.URLDecoder.decode(url, "UTF-8"); taps += "githubCard ${o.kind} ${o.bits}"; true },
    )

    private fun page(args: FeedbackArgs = about, u: FeedbackUi? = ui, prefill: String? = null, dark: Boolean = false) =
        rule.show(dark = dark) { FeedbackScreen(args, u, actions, onBack = { backs++ }, prefill = prefill, newRef = { "RFB-ABCD2345" }) }

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
        // 1.2: the chip's own line, under the phone's.
        assertTrue(whole, whole.contains("\nChip: Build.SOC_MANUFACTURER “Google” · Build.SOC_MODEL “Tensor G4”\n"))
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

    /** 1.4, the card: the turn hidden as it goes, each part with its tick, the guess changeable, the header v2. */
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theCardSendsTheTurnHiddenAndOnlyWhatIsTicked() {
        val turn = app.heyrobot.core.feedback.ReportTurn("text Marie Tremblay that I'm late", "Texting Marie Tremblay now.",
            app.heyrobot.core.ToolName.SEND_TEXT, app.heyrobot.core.ActionStatus.DRAFT_OPENED, "en", "en", at = 1)
        rule.show { FeedbackScreen(FeedbackArgs(FeedbackEntry.VOICE, FeedbackKind.BUG, null), ui.copy(names = listOf("Marie Tremblay")), actions,
            onBack = {}, newRef = { "RFB-ABCD2345" }, turn = turn) }
        rule.onAllNodes(hasText("Marie", substring = true)).assertCountEquals(0)
        scrollTo("Wrong answer")
        rule.onNodeWithText("Wrong answer").performClick()
        scrollTo("Share")
        rule.onNodeWithText("Share").performClick()
        val first = outs.last().text
        assertTrue(first, first.contains("\nFeature: Texts, email and calls\nProblem: Wrong answer\nParts: what you said, my answer, what I did, facts, ") &&
            first.contains("What I did:\nsend_text · DRAFT_OPENED\n") && first.contains(ReportHide.NAME) && !first.contains("Marie"))
        scrollTo("My answer")
        box("My answer").performClick()
        scrollTo("Hide names and numbers")
        rule.onNodeWithText("Hide names and numbers").performClick()
        scrollTo("Share")
        rule.onNodeWithText("Share").performClick()
        val second = outs.last().text
        assertTrue(second, second.contains("Parts: what you said, what I did, facts, ") && second.contains("text Marie Tremblay that I'm late") &&
            !second.contains("My answer:"))
    }

    /**
     * RP3: the turn arrives after the card opened (the panel's report_turn.json is read off the main thread), and the first
     * frame that shows it already has the ticks the card ends with: my answer unticked after a read, never a frame ticked.
     */
    @Test @Config(qualifiers = "w411dp-h4200dp-mdpi")
    fun theFirstFrameShowsTheTicksTheCardEndsWith() {
        val arrived = androidx.compose.runtime.mutableStateOf<app.heyrobot.core.feedback.ReportTurn?>(null)
        rule.show { FeedbackScreen(FeedbackArgs(FeedbackEntry.VOICE, FeedbackKind.BUG, null), ui, actions, onBack = {},
            newRef = { "RFB-ABCD2345" }, turn = arrived.value) }
        rule.waitForIdle()
        // The clock is paused only to read the first frame that shows the boxes (at most 10 frames). The page is tall enough
        // that they are composed without scrolling: a lazy list's scroll waits for frames a paused clock never gives (the hang).
        rule.mainClock.autoAdvance = false
        arrived.value = app.heyrobot.core.feedback.ReportTurn("read my messages from Mom", "Mom said: I'm at Jen's.", at = 1, other = true)
        var frames = 0
        do {
            rule.mainClock.advanceTimeByFrame()
            assertTrue("the card's boxes never showed", ++frames <= 10)
        } while (rule.onAllNodes(hasText("What you said") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
                .fetchSemanticsNodes().isEmpty())
        box("What you said").assertIsOn()
        box("My answer").assertIsOff()
        // This turn ran no tool: "What I did" has nothing to send, so its box is off and can't be ticked (ReportCard.part).
        box("What I did").assertIsOff().assertIsNotEnabled()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        scrollTo("My answer")
        box("What you said").assertIsOn()
        box("My answer").assertIsOff()
        box("What I did").assertIsOff().assertIsNotEnabled()
    }

    /** RP3: the crash offer opens the card: the sort and the facts with their tick, though no turn came with it. */
    @Test fun theCrashOfferOpensTheCard() {
        page(FeedbackArgs(FeedbackEntry.CRASH, FeedbackKind.BUG, t))
        scrollTo("Report a problem")
        rule.onNodeWithText("Report a problem").assertExists()
        scrollTo("What goes with it")
        rule.onNodeWithText("What goes with it").assertExists()
    }

    @Test fun theGitHubIssueFollowsTheFactsTick() {
        val turn = app.heyrobot.core.feedback.ReportTurn("set a timer", "Timer set.", at = 1)
        rule.show { FeedbackScreen(FeedbackArgs(FeedbackEntry.VOICE, FeedbackKind.BUG, null), ui.copy(github = repo), actions, onBack = {}, newRef = { "RFB-ABCD2345" }, turn = turn) }
        scrollTo("Open a GitHub issue")
        rule.onNodeWithText("Opens a new issue in your browser with only its title, and puts the report on your clipboard", substring = true).assertExists()
        rule.onNodeWithText("Open a GitHub issue").performClick()
        // The link has the title only; the report (what Show shows) goes on the clipboard, and it follows the facts tick.
        assertEquals(1, links.size)
        assertTrue(links.last(), links.last().startsWith("https://github.com/picocryptfan/robot-assistant/issues/new?title=Bug report: RFB-ABCD2345") &&
            !links.last().contains("&report=") && !links.last().contains("&body=") && !links.last().contains("Pixel 9a") && !links.last().contains("set a timer"))
        assertTrue(outs.last().text, outs.last().text.contains("Pixel 9a") && outs.last().text.contains("set a timer"))
        scrollTo("Facts about this phone")
        box("Facts about this phone").performClick()
        scrollTo("Open a GitHub issue")
        rule.onNodeWithText("Open a GitHub issue").performClick()
        assertEquals(links[0], links.last())
        assertTrue(outs.last().text, !outs.last().text.contains("Pixel 9a") && outs.last().text.contains("0.7.0 (70)"))
        assertTrue(taps.toString(), taps.none { it.startsWith("github ") })
    }

    @Test fun aCardSendsOnlyOnceTheNamesHaveLoaded() {
        val turn = app.heyrobot.core.feedback.ReportTurn("text Marie", "Texting Marie.", at = 1)
        var u by mutableStateOf(ui.copy(named = false))
        rule.show { FeedbackScreen(FeedbackArgs(FeedbackEntry.VOICE, FeedbackKind.BUG, null), u, actions, onBack = {}, newRef = { "RFB-ABCD2345" }, turn = turn) }
        scrollTo("Share")
        rule.onNodeWithText("Share").assertIsNotEnabled()
        u = ui.copy(names = listOf("Marie"))
        rule.waitForIdle()
        rule.onNodeWithText("Share").assertIsEnabled()
    }

    /** Review L4: with Hide names on, the turn never shows before the names have loaded; its parts say so meanwhile. */
    @Test fun theTurnWaitsForTheNamesBeforeItShows() {
        val turn = app.heyrobot.core.feedback.ReportTurn("text Marie", "Texting Marie.", at = 1)
        var u by mutableStateOf<FeedbackUi?>(null)
        rule.show { FeedbackScreen(FeedbackArgs(FeedbackEntry.VOICE, FeedbackKind.BUG, null), u, actions, onBack = {}, newRef = { "RFB-ABCD2345" }, turn = turn) }
        rule.onAllNodes(hasText("Marie", substring = true)).assertCountEquals(0)
        scrollTo("What you said")
        assertTrue(rule.onAllNodes(hasText("Reading your contacts…")).fetchSemanticsNodes().isNotEmpty())
        u = ui.copy(named = false)
        rule.waitForIdle()
        rule.onAllNodes(hasText("Marie", substring = true)).assertCountEquals(0)
        u = ui.copy(names = listOf("Marie"))
        rule.waitForIdle()
        rule.onAllNodes(hasText("Marie", substring = true)).assertCountEquals(0)
        assertTrue(rule.onAllNodes(hasText(ReportHide.NAME, substring = true)).fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodes(hasText("Reading your contacts…")).assertCountEquals(0)
    }

    @Test fun theHideNoteSaysWhatIsHidden() {
        val turn = app.heyrobot.core.feedback.ReportTurn("text Marie", "Texting Marie.", at = 1)
        var u by mutableStateOf(ui)
        rule.show { FeedbackScreen(FeedbackArgs(FeedbackEntry.VOICE, FeedbackKind.BUG, null), u, actions, onBack = {}, newRef = { "RFB-ABCD2345" }, turn = turn) }
        scrollTo("Hide names and numbers")
        // fix-seal-14 L3: each case shows its own note, whole, and each says exactly what goes hidden and what doesn't.
        val tail = " Places, and what I read from your other apps, go as shown. Your words below go as they are."
        fun note(text: String) { rule.waitForIdle(); rule.onNodeWithText(text + tail).assertExists() }
        note("Long numbers, the numbers of your contacts and People, dates with their month and times said in words go as [number], " +
            "emails and addresses (your contacts' too) as [address], and the names of your contacts and People as [name], as shown above.")
        // Without the Contacts permission: People's names still go as [name]; the note says to check, never asks for the permission.
        u = ui.copy(contacts = false)
        note("Long numbers, People's numbers, dates with their month and times said in words go as [number], emails and addresses as [address], " +
            "and the names in People as [name], as shown above. Names, short numbers and addresses that are only in your contacts aren't hidden, " +
            "because I can't read your contacts: check with Show before you send.")
        rule.onAllNodes(hasText("the names of your contacts", substring = true)).assertCountEquals(0)
        // report-hide-people: People unread, only its names and short numbers go unhidden (its emails and XMPP addresses hide by their shape).
        u = ui.copy(peopleRead = peopleRead(people = true, folks = null))
        note("Long numbers, your contacts' numbers, dates with their month and times said in words go as [number], " +
            "emails and addresses (your contacts' too) as [address], and the names of your contacts as [name], as shown above. " +
            "Names and short numbers that are only in People aren't hidden, because I can't read People: check with Show before you send.")
        // Neither read: L3's form (534d2aac2).
        u = ui.copy(contacts = false, peopleRead = peopleRead(people = true, folks = null))
        note("Long numbers, dates with their month and times said in words go as [number], emails and addresses as [address], as shown above. " +
            "The names and short numbers in your contacts and People, and your contacts' addresses, aren't hidden, " +
            "because I can't read your contacts or People: check with Show before you send.")
        rule.onAllNodes(hasText("Names aren't hidden", substring = true)).assertCountEquals(0)
        val inPart = ReportPeople.of(emptyList(), whole = false)
        // fix-seal-14-d 1: read in part, what was read is hidden and the note says some of People, never "I can't read People".
        u = ui.copy(peopleRead = peopleRead(people = true, folks = inPart))
        note("Long numbers, the numbers of your contacts and People, dates with their month and times said in words go as [number], " +
            "emails and addresses (your contacts' too) as [address], and the names of your contacts and People as [name], as shown above. " +
            "I can't read some of People, so names and short numbers only there aren't hidden: check with Show before you send.")
        u = ui.copy(contacts = false, peopleRead = peopleRead(people = true, folks = inPart))
        note("Long numbers, People's numbers, dates with their month and times said in words go as [number], emails and addresses as [address], " +
            "and the names in People as [name], as shown above. Names, short numbers and addresses that are only in your contacts, and names and " +
            "short numbers only in some of People, aren't hidden, because I can't read your contacts or some of People: check with Show before you send.")
        rule.onAllNodes(hasText("because I can't read People", substring = true)).assertCountEquals(0)
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
        rule.setShotContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) {
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

    @Test fun aboutHasTheSendFeedbackRow() {
        val log = ActionLog()
        rule.show { AboutSettingsScreen(log.actions, onBack = {}) }
        rule.onRow("Report a problem or send an idea").assert(hasText("A bug or an idea, to the developer: you choose the app it goes with")).performClick()
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

    /** Item 7: a contact's number (its 5-digit tail too, spaced or dashed) and address hide on the card, as their names do. */
    @Test fun theCardHidesContactsNumbersAndAddresses() {
        val turn = app.heyrobot.core.feedback.ReportTurn("call 55 01 99", "Driving to The Old Mill. Dialling 5-01-99.", at = 1)
        rule.show { FeedbackScreen(FeedbackArgs(FeedbackEntry.VOICE, FeedbackKind.BUG, null), ui.copy(numbers = listOf("+1 902-555-0199"),
            addresses = listOf("The Old Mill\nPleasant Bay NS")), actions, onBack = {}, newRef = { "RFB-ABCD2345" }, turn = turn) }
        scrollTo("Share")
        rule.onNodeWithText("Share").performClick()
        val out = outs.last().text
        assertTrue(out, out.contains("call [number]") && !out.contains("01-99") && !out.contains("Old Mill") && out.contains("[address]"))
    }
}
