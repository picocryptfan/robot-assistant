// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import app.heyrobot.core.BrainModel
import app.heyrobot.core.GpuFailure
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.LookSettings
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.Phone
import app.heyrobot.core.ProblemStage
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.WakeAction
import app.heyrobot.core.update.CheckOutcome
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.core.update.InstallOutcome
import app.heyrobot.core.update.UpdateFail
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.time.ZoneId

/** 0.7, Send feedback: the message's fixed header, its parts, what each kind ticks, the length it keeps to, and its GitHub link. */
class FeedbackReportTest {
    private val utc = ZoneId.of("UTC")
    private val t = 1_791_000_000_000L
    private val nineA = Phone("Google", "Pixel 9a", "Google", "Tensor G4", 7_600_000_000L)
    private val facts = FeedbackFacts(
        robot = FeedbackFacts.robot("0.7.0", 70, debug = false),
        phone = FeedbackFacts.phone(nineA, "16", "BP2A.250805.005", "2025081900"),
        model = FeedbackFacts.model(BrainModel.GENERAL, ModelBackend.CPU, GpuFailure(BrainModel.GENERAL, t, ProblemStage.WRITING, null), null, zone = utc),
        look = FeedbackFacts.look(wallpaperColors = true),
        language = FeedbackFacts.language("en-CA"),
    )
    private val ref = "RFB-ABCD2345"
    private val log = List(80) { i -> VoiceLogEntry(t - i * 1_000L, VoiceEvent.WAKE, 2, -1, WakeAction.OPENED.id) }
    private val blocks = listOf(
        FeedbackDiagnostics.problem("Robot problem report\nFast stopped on the GPU, so Robot switched to the CPU.\n\nWhen: 2026-10-01"),
        FeedbackDiagnostics.voiceLog(log, utc),
        FeedbackDiagnostics.settings(RobotSettings(), LookSettings(), UpdateSettings()),
        FeedbackDiagnostics.updater(UpdateState(lastCheck = t, outcome = CheckOutcome.FAILED, fail = UpdateFail.NOT_FOUND, failHttp = 404), utc),
    )

    @Test fun theHeaderIsFixedAndParsable() {
        val d = FeedbackDraft(FeedbackKind.BUG, "The timer didn't ring.", steps = "Said “set a timer for 2 minutes”.", expected = "A ring after 2 minutes.",
            ticked = Diagnostic.entries.toSet())
        val c = FeedbackReport.compose(d, ref, facts, blocks)
        val lines = c.text.lines()
        assertEquals(listOf(
            "ROBOT FEEDBACK v1",
            "Kind: Bug report",
            "Ref: RFB-ABCD2345",
            "Robot: 0.7.0 (70) · release build",
            "Phone: Pixel 9a · Tensor G4 · Android 16 (BP2A.250805.005) · GrapheneOS",
            "Model: Fast · Gemma 4 E2B · GPU (fell back to CPU on 2026-10-03)",
            "Look: wallpaper colours · Language: en-CA",
            "---",
            "What happened:",
            "The timer didn't ring.",
            "---",
            "Steps:",
            "Said “set a timer for 2 minutes”.",
            "Expected:",
            "A ring after 2 minutes.",
            "---",
            "Diagnostics (ticked by the tester):",
            "• Last problem report:",
            "Robot problem report",
        ), lines.take(19))
        assertTrue(c.text.contains("• Voice log, last 60 events, newest first (codes and numbers only):\n"))
        assertTrue(c.text.contains("• Settings summary (switches and levels only):\n"))
        assertTrue(c.text.contains("• Updater (codes only):\nlastCheck=2026-10-03 04:00 · outcome=FAILED · error=NOT_FOUND · http=404\n"))
        assertTrue(c.text.endsWith("---\nForward this whole message to the developer's assistant as it is.\n"))
        // Each diagnostic's part is in the message exactly as its preview shows it, in order.
        val order = Diagnostic.entries.map { c.text.indexOf(c.blocks.getValue(it)) }
        assertTrue(order.toString(), order.all { it > 0 } && order == order.sorted())
        assertEquals(emptySet<Diagnostic>(), c.cut)
        assertFalse(c.textCut)
    }

    @Test fun eachKindHasItsHeadingAndOnlyABugHasSteps() {
        val idea = FeedbackReport.compose(FeedbackDraft(FeedbackKind.IDEA, "Read my calendar in the brief", steps = "ignored"), ref, facts, blocks).text
        assertTrue(idea.contains("Kind: Idea\n") && idea.contains("---\nThe idea:\nRead my calendar in the brief\n---\nDiagnostics: none ticked by the tester\n"))
        assertFalse(idea.contains("Steps:"))
        val other = FeedbackReport.compose(FeedbackDraft(FeedbackKind.OTHER, ""), ref, facts, blocks).text
        assertTrue(other.contains("Kind: Other\n") && other.contains("Message:\n(nothing written)\n"))
        // A bug without steps or what was expected has no such part; with one of them, the other says none.
        assertFalse(FeedbackReport.compose(FeedbackDraft(FeedbackKind.BUG, "x"), ref, facts, blocks).text.contains("Steps:"))
        assertTrue(FeedbackReport.compose(FeedbackDraft(FeedbackKind.BUG, "x", expected = "y"), ref, facts, blocks).text
            .contains("Steps:\n(none written)\nExpected:\ny\n"))
        // Your words as you wrote them, line breaks kept (Windows ones made plain).
        assertTrue(FeedbackReport.compose(FeedbackDraft(FeedbackKind.BUG, "  one\r\ntwo  "), ref, facts, blocks).text.contains("What happened:\none\ntwo\n"))
    }

    @Test fun whatEachKindTicks() {
        assertEquals(setOf(Diagnostic.PROBLEM, Diagnostic.VOICE_LOG, Diagnostic.SETTINGS), Diagnostic.defaults(FeedbackKind.BUG, FeedbackEntry.ABOUT))
        assertEquals(Diagnostic.entries.toSet(), Diagnostic.defaults(FeedbackKind.BUG, FeedbackEntry.UPDATER))
        assertEquals(emptySet<Diagnostic>(), Diagnostic.defaults(FeedbackKind.IDEA, FeedbackEntry.VOICE))
        assertEquals(emptySet<Diagnostic>(), Diagnostic.defaults(FeedbackKind.OTHER, FeedbackEntry.ABOUT))
        assertEquals(15, Diagnostic.bits(Diagnostic.entries.toSet()))
        assertEquals(setOf(Diagnostic.VOICE_LOG, Diagnostic.SETTINGS), Diagnostic.of(6))
        assertEquals("the voice log, the settings summary", Diagnostic.words(6))
        assertEquals("no diagnostics", Diagnostic.words(0))
        // Only what is ticked goes in, and only what Robot could add (no problem: no problem report).
        val c = FeedbackReport.compose(FeedbackDraft(FeedbackKind.BUG, "x", ticked = setOf(Diagnostic.PROBLEM, Diagnostic.SETTINGS)), ref, facts, blocks.drop(1))
        assertEquals(setOf(Diagnostic.SETTINGS), c.blocks.keys)
        assertFalse(c.text.contains("Voice log") || c.text.contains("problem report"))
    }

    @Test fun theFacts() {
        assertEquals("0.7.0 (70) · release build", FeedbackFacts.robot("0.7.0", 70, false))
        assertEquals("0.7.0-rc1 (70) · test build", FeedbackFacts.robot("0.7.0-rc1", 70, false))
        assertEquals("0.7.0 (70) · debug build", FeedbackFacts.robot("0.7.0", 70, true))
        assertEquals("GrapheneOS", FeedbackFacts.os("2026100201"))
        assertEquals("not GrapheneOS (by its build number)", FeedbackFacts.os("13470043"))
        assertEquals("Pixel 10 Pro · Tensor G5 · Android 16 (BP3A.251005.004) · not GrapheneOS (by its build number)",
            FeedbackFacts.phone(Phone("Google", "Pixel 10 Pro", "Google", "Tensor G5"), "16", "BP3A.251005.004", "14012345"))
        assertEquals("Fast · Gemma 4 E2B (Tensor G5) · TPU", FeedbackFacts.model(BrainModel.FAST, ModelBackend.TPU, null, null))
        assertEquals("Smarter, slower · Gemma 4 E4B (CPU) · CPU", FeedbackFacts.model(BrainModel.SMARTER, ModelBackend.CPU, null, null))
        // Your own file: its kind, never its name.
        assertEquals("Your own model file (.gguf) · CPU", FeedbackFacts.model(BrainModel.OWN, ModelBackend.CPU, null, ModelFormat.GGUF))
        // A GPU failure of another model doesn't count; a chosen model that isn't ready says what Robot uses.
        assertEquals("Smarter, slower · Gemma 4 E4B (CPU) · CPU · not ready: Robot uses Fast · Gemma 4 E2B",
            FeedbackFacts.model(BrainModel.SMARTER, ModelBackend.CPU, GpuFailure(BrainModel.GENERAL, t, ProblemStage.LOADING, LoadFailure.ENGINE), null,
                using = BrainModel.GENERAL))
        assertEquals("Robot's colours", FeedbackFacts.look(wallpaperColors = false))
        assertEquals("fr-CA", FeedbackFacts.language("fr-CA"))
        // A line of the header never breaks it.
        assertEquals("ab", FeedbackFacts.line("a\nb", 10))
    }

    @Test fun theReferenceIsRandomBase32() {
        val a = FeedbackRef.new()
        assertTrue(a, FeedbackRef.valid(a) && Regex("""RFB-[A-Z2-7]{8}""").matches(a))
        assertNotEquals(a, FeedbackRef.new())
        assertEquals(FeedbackRef.new(java.util.Random(7)), FeedbackRef.new(java.util.Random(7)))
        assertFalse(FeedbackRef.valid("RFB-abcd2345") || FeedbackRef.valid("RFB-ABCD2341") || FeedbackRef.valid(null))
    }

    /** Under 12,000 characters: the voice log's oldest events go first, then the problem report's end; never your words. */
    @Test fun theMessageKeepsToItsLengthCuttingTheVoiceLogFirst() {
        val all = Diagnostic.entries.toSet()
        val long = FeedbackReport.compose(FeedbackDraft(FeedbackKind.BUG, "x", ticked = all), ref, facts,
            blocks.map { if (it.diagnostic == Diagnostic.PROBLEM) FeedbackDiagnostics.problem((1..200).joinToString("\n") { "report line $it " + "-".repeat(50) }) else it })
        assertTrue(long.text.length.toString(), long.text.length <= FeedbackReport.MAX_CHARS)
        // The whole voice log went before the report lost its end, which it keeps from the start.
        assertEquals(setOf(Diagnostic.VOICE_LOG, Diagnostic.PROBLEM), long.cut)
        assertTrue(long.text.contains("(the 60 older events left out to keep the message short)") && long.text.contains("report line 1 -"))
        assertTrue(Regex("""\(\d+ more lines left out to keep the message short\)""").containsMatchIn(long.blocks.getValue(Diagnostic.PROBLEM)))
        // Your words are never cut, even when they alone are longer than that.
        val words = "word ".repeat(3_000).trim()
        val c = FeedbackReport.compose(FeedbackDraft(FeedbackKind.BUG, words, ticked = all), ref, facts, blocks)
        assertTrue(c.text.contains(words))
        assertFalse(c.textCut)
        assertEquals(all, c.cut)
        assertTrue(c.text.contains("(the 60 older events left out to keep the message short)"))
        // A short report keeps everything; a medium one loses only voice log events, newest kept.
        val medium = FeedbackReport.compose(FeedbackDraft(FeedbackKind.BUG, "w".repeat(8_000), ticked = all), ref, facts, blocks)
        assertTrue(medium.text.length <= FeedbackReport.MAX_CHARS)
        assertEquals(setOf(Diagnostic.VOICE_LOG), medium.cut)
        assertTrue(medium.text.contains(FeedbackDiagnostics.logLine(log.first(), utc)))
        assertFalse(medium.text.contains(FeedbackDiagnostics.logLine(log[59], utc)))
        assertTrue(Regex("""\(the \d+ older events left out to keep the message short\)""").containsMatchIn(medium.text))
        // Before the voice log is cut, nothing else is.
        assertTrue(medium.text.contains("Robot problem report\nFast stopped on the GPU"))
    }

    @Test fun aGitHubIssueLinkFitsAndSaysWhatItCut() {
        val repo = GitHubRepo("picocryptfan", "robot-assistant")
        val d = FeedbackDraft(FeedbackKind.BUG, "The timer didn't ring & the panel froze?", ticked = Diagnostic.entries.toSet())
        val link = FeedbackReport.issue(repo, d, ref, facts, blocks)
        assertTrue(link.url.startsWith("https://github.com/picocryptfan/robot-assistant/issues/new?title=Bug%20report%3A%20The%20timer%20didn%27t%20ring%20%26"))
        val body = link.url.substringAfter("&report=")
        assertTrue(body.length.toString(), body.length <= FeedbackReport.GITHUB_MAX)
        assertTrue(link.cut)
        val text = URLDecoder.decode(body, "UTF-8")
        assertTrue(text.startsWith("ROBOT FEEDBACK v1\nKind: Bug report\n") && text.contains("The timer didn't ring & the panel froze?"))
        assertEquals(text.length, link.chars)
        // Your words are cut only for a link, last, and it says so.
        val long = FeedbackReport.issue(repo, d.copy(text = "é".repeat(5_000)), ref, facts, blocks)
        assertTrue(long.url.substringAfter("&report=").length <= FeedbackReport.GITHUB_MAX)
        assertTrue(URLDecoder.decode(long.url.substringAfter("&report="), "UTF-8").contains(FeedbackReport.TEXT_CUT))
        // A short idea goes whole, and the title is the kind with the start of your words.
        val idea = FeedbackReport.issue(repo, FeedbackDraft(FeedbackKind.IDEA, "Read my calendar in the brief\nand the weather"), ref, facts, blocks)
        assertFalse(idea.cut)
        assertEquals("Idea: Read my calendar in the brief", URLDecoder.decode(idea.url.substringAfter("title=").substringBefore("&template="), "UTF-8"))
        assertTrue(link.url.contains("&template=bug_report.yml&labels=bug&report="))
        assertTrue(idea.url.contains("&template=idea.yml&labels=idea&idea="))
        assertEquals("Bug report: RFB-ABCD2345", FeedbackReport.issueTitle(FeedbackDraft(FeedbackKind.BUG, ""), ref))
    }

    @Test fun theVoiceLogIsCodesAndTheLast60() {
        val b = FeedbackDiagnostics.voiceLog(log, utc)
        assertEquals(60, b.lines.size)
        assertEquals("10-03 04:00:00  WAKE 2 -1 0  Wake word #2: opened the panel", b.lines.first())
        assertEquals(listOf("(no events)"), FeedbackDiagnostics.voiceLog(emptyList(), utc).lines)
    }

    @Test fun theUpdatersLinesAreCodes() {
        val st = UpdateState(lastCheck = t, outcome = CheckOutcome.AVAILABLE, refusedWhy = UpdateFail.CHECKSUM_MISMATCH, install = InstallOutcome.FAILED,
            installVersion = "0.7.1<script>")
        assertTrue(FeedbackDiagnostics.updaterFailed(st))
        assertFalse(FeedbackDiagnostics.updaterFailed(UpdateState(lastCheck = t, outcome = CheckOutcome.UP_TO_DATE)))
        val lines = FeedbackDiagnostics.updater(st, utc).lines
        assertEquals(listOf("lastCheck=2026-10-03 04:00 · outcome=AVAILABLE · error=none", "refusedFile=CHECKSUM_MISMATCH", "lastInstall=FAILED (0.7.1script)"), lines)
    }

    @Test fun theSendToNumberAndItsFile() {
        assertEquals("+15145550123", SendTo.normalize("+1 (514) 555-0123"))
        assertEquals("5145550123", SendTo.normalize("514.555.0123"))
        assertNull(SendTo.normalize("mom"))
        assertNull(SendTo.normalize("12"))
        assertEquals(FeedbackPrefs("+15145550123"), FeedbackPrefs.fromJson(FeedbackPrefs("+15145550123").toJson()))
        assertEquals(FeedbackPrefs(), FeedbackPrefs.fromJson("{\"sendTo\":\"not a number\"}"))
        assertEquals(FeedbackPrefs(), FeedbackPrefs.fromJson("damaged"))
        assertEquals("{\"v\":1}", FeedbackPrefs().toJson())
        val p = FeedbackPrefill(FeedbackKind.IDEA, "Read my calendar in the brief", t)
        assertEquals(p, FeedbackPrefill.fromJson(p.toJson()))
        assertTrue(p.fresh(t + 60_000) && !p.fresh(t + 121_000) && !p.fresh(t - 1))
        assertNull(FeedbackPrefill.fromJson("{\"kind\":\"NOPE\",\"text\":\"x\",\"at\":1}"))
    }
}
