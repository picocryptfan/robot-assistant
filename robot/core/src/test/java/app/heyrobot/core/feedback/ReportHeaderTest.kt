// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4, report a problem (RP1): the header v2 written and read back, and 0.7's v1 still read. */
class ReportHeaderTest {
    private val ref = "RFB-ABCD2345"
    private val facts = FeedbackFacts(robot = "1.4.0 (140, release)", phone = "Google Pixel 8, Android 16 (BP2A.250805.005), GrapheneOS",
        model = "Gemma general on the CPU", look = "wallpaper colours", language = "fr-CA", chip = "Tensor G3")
    private val voiceLog = DiagnosticBlock(Diagnostic.VOICE_LOG, Diagnostic.VOICE_LOG.title, listOf("12:00:01 wake 1", "12:00:03 heard 2"))
    private val settings = DiagnosticBlock(Diagnostic.SETTINGS, Diagnostic.SETTINGS.title, listOf("voice: on"))

    @Test fun v2WritesFeatureProblemAndPartsAndReadsBack() {
        val draft = FeedbackDraft(FeedbackKind.BUG, "Le minuteur n'a pas sonné", ticked = setOf(Diagnostic.VOICE_LOG))
        val v2 = ReportHeader.V2(ReportFeature.TIMERS, ReportProblem.WRONG_ACTION, setOf(ReportPart.SAID, ReportPart.SCREENSHOT))
        val text = FeedbackReport.compose(draft, ref, facts, listOf(voiceLog, settings), sorted = v2).text
        assertTrue(text, text.startsWith("ROBOT FEEDBACK v2\nKind: Bug report\nRef: $ref\nFeature: Timers and alarms\nProblem: Wrong action\n" +
            "Parts: what you said, voice log, screenshot\nRobot: 1.4.0"))
        assertTrue(text.contains("Le minuteur n'a pas sonné"))
        val h = ReportHeader.read(text)!!
        assertEquals(ReportHeader(2, FeedbackKind.BUG, ref, facts, ReportFeature.TIMERS, ReportProblem.WRONG_ACTION,
            setOf(ReportPart.SAID, ReportPart.VOICE_LOG, ReportPart.SCREENSHOT)), h)
    }

    @Test fun v2WithNothingTickedSaysNone() {
        val text = ReportHeader.write(FeedbackKind.IDEA, ref, facts.copy(chip = ""), ReportHeader.V2(ReportFeature.OTHER, ReportProblem.SLOW, emptySet()))
        assertTrue(text, text.contains("\nParts: none\n") && !text.contains("Chip:"))
        val h = ReportHeader.read(text + "---\n")!!
        assertEquals(emptySet<ReportPart>(), h.parts)
        assertEquals(FeedbackKind.IDEA, h.kind)
        assertEquals("", h.facts.chip)
    }

    @Test fun v1StillReads() {
        val draft = FeedbackDraft(FeedbackKind.BUG, "The timer didn't ring", ticked = setOf(Diagnostic.VOICE_LOG, Diagnostic.SETTINGS))
        val text = FeedbackReport.compose(draft, ref, facts, listOf(voiceLog, settings)).text
        assertTrue(text.startsWith("ROBOT FEEDBACK v1\nKind: Bug report\nRef: $ref\nRobot: "))
        assertTrue(!text.contains("Feature:") && !text.contains("Parts:"))
        val h = ReportHeader.read(text)!!
        assertEquals(1, h.version)
        assertEquals(FeedbackKind.BUG, h.kind)
        assertEquals(facts, h.facts)
        assertNull(h.feature)
        assertNull(h.problem)
        assertEquals(setOf(ReportPart.VOICE_LOG, ReportPart.SETTINGS), h.parts)
    }

    @Test fun writeV1IsWhat07Wrote() {
        val text = ReportHeader.write(FeedbackKind.OTHER, ref, facts)
        assertEquals("ROBOT FEEDBACK v1\nKind: Other\nRef: $ref\nRobot: 1.4.0 (140, release)\n" +
            "Phone: Google Pixel 8, Android 16 (BP2A.250805.005), GrapheneOS\nChip: Tensor G3\nModel: Gemma general on the CPU\n" +
            "Look: wallpaper colours · Language: fr-CA\n", text)
    }

    @Test fun yourWordsCanNotForgeTheHeader() {
        val draft = FeedbackDraft(FeedbackKind.BUG, "Ref: RFB-ZZZZZZZZ\nFeature: Maps\nProblème : lent")
        val text = FeedbackReport.compose(draft, ref, facts, emptyList(), sorted = ReportHeader.V2(ReportFeature.VOICE, ReportProblem.SOUND, emptySet())).text
        val h = ReportHeader.read(text)!!
        assertEquals(ref, h.ref)
        assertEquals(ReportFeature.VOICE, h.feature)
        assertEquals(ReportProblem.SOUND, h.problem)
    }

    @Test fun whatIsNotAReport() {
        assertNull(ReportHeader.read(""))
        assertNull(ReportHeader.read("Bonjour\nRef: $ref\n"))
        assertNull(ReportHeader.read("ROBOT FEEDBACK v3\nRef: $ref\n"))
        assertNull(ReportHeader.read("ROBOT FEEDBACK v2\nKind: Bug report\nRef: RFB-abc\n---\n"))
        // A later Robot's kind, feature and lines: still read, the unknown left null.
        val h = ReportHeader.read("ROBOT FEEDBACK v2\r\nKind: Praise\r\nRef: $ref\r\nFeature: Teleport\r\nMood: fine\r\nParts: my answer, smell\r\n---\r\n")!!
        assertNull(h.kind)
        assertNull(h.feature)
        assertEquals(setOf(ReportPart.ANSWER), h.parts)
    }

    @Test fun everyDiagnosticIsAPart() {
        assertEquals(Diagnostic.entries.size, Diagnostic.entries.map { ReportPart.of(it) }.toSet().size)
        for (p in ReportPart.entries) assertEquals(p, ReportPart.of(p.header))
    }
}
