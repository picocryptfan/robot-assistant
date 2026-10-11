package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Robot's own summary under Gemma's 4,096-token window (docs/conversation-mode.md 7.1). */
class SummaryPlannerTest {
    private val zone = ZoneOffset.UTC
    private val start = ZonedDateTime.of(2026, 10, 11, 14, 0, 0, 0, zone).toInstant().toEpochMilli()

    private val english = listOf("I think we should leave before the traffic gets worse, it's already four thirty.",
        "Can you call the garage tomorrow morning and ask about the brakes?", "Sure, I'll do it first thing, around eight.",
        "And don't forget the dentist on Thursday at two.")
    /** Accent-heavy French: every accented letter counts as a token of its own. */
    private val french = listOf("Ça été très compliqué à gérer, j'étais épuisé après la réunion de février à Québec.",
        "Hélène a préféré déléguer ça à l'équipe d'été, ç'a été évité de justesse.", "Révisé, élégant, réécrit: très économique à éditer.")

    /** [minutes] of talk, about 12 lines a minute, two speakers. */
    private fun talk(minutes: Int, said: List<String>): Pair<TranscriptMeta, Transcript> {
        val f = TranscriptFold()
        var i = 0
        for (m in 0 until minutes) for (k in 0 until 12) {
            val t0 = m * 60.0 + k * 5
            f.apply(TranscriptEvent.Line(i, t0, t0 + 4, if (k % 2 == 0) "S1" else "S2", if (said === french) "fr" else "en", said[i % said.size]))
            i++
        }
        f.apply(TranscriptEvent.Rename("S1", "Marie"))
        return TranscriptMeta("20261011-1400-0001", "Talk", start, start + minutes * 60_000L, SessionStatus.ENDED) to f.result()
    }

    private fun plan(minutes: Int, said: List<String>, lang: String = "en"): SummaryPlan {
        val (meta, t) = talk(minutes, said)
        return SummaryPlanner.plan(SummaryPlanner.lines(meta, t, zone), lang)
    }

    @Test fun everyCallFitsTheWindowInEnglishAndInFrench() {
        for ((said, lang) in listOf(english to "en", french to "fr", french to "en")) {
            val p = plan(60, said, lang)
            assertTrue(p.parts > 1)
            for (c in p.map) {
                assertTrue(SummaryPlanner.fits(c))
                assertTrue(TokenEstimate.text(c.user) <= SummaryPlanner.CHUNK_TOKENS + 60)
                assertEquals(SummaryPlanner.NOTE_TOKENS, c.maxChunks)
                assertEquals(Prompt.summaryMap(lang), c.system)
            }
        }
        // Accents cost more tokens, so French needs more parts for the same talk.
        assertTrue(plan(60, french).parts > plan(60, english).parts)
    }

    @Test fun partsAreCutOnlyBetweenLinesAndSayWhenTheyAre() {
        val (meta, t) = talk(30, english)
        val lines = SummaryPlanner.lines(meta, t, zone)
        assertEquals("[14:00] Marie (en): I think we should leave before the traffic gets worse, it's already four thirty.", lines[0])
        assertEquals("[14:00] Speaker 2 (en): Can you call the garage tomorrow morning and ask about the brakes?", lines[1])
        val p = SummaryPlanner.plan(lines, "en")
        val back = p.map.flatMap { it.user.substringAfter(":\n").split("\n") }
        assertEquals(lines, back)
        assertTrue(p.map[0].user, p.map[0].user.startsWith("Part 1 of ${p.parts} (14:00–"))
        assertTrue(p.map.last().user.startsWith("Part ${p.parts} of ${p.parts} ("))
        // One part for a few minutes.
        assertEquals(1, plan(3, english).parts)
        assertEquals(1, plan(3, english).levels)
    }

    @Test fun aLineTooLongForAPartIsCutBetweenWords() {
        val long = "[14:00] Marie (fr): " + (1..3_000).joinToString(" ") { "mot$it" }
        val parts = SummaryPlanner.chunks(listOf("[13:59] Speaker 2 (en): hi", long), Prompt.SUMMARY_MAP)
        assertTrue(parts.size > 2)
        val pieces = parts.flatten().drop(1)
        assertTrue(pieces.all { it.startsWith("[14:00] Marie (fr): ") })
        assertEquals((1..3_000).joinToString(" ") { "mot$it" }, pieces.joinToString(" ") { it.removePrefix("[14:00] Marie (fr): ") })
        for (p in parts) assertTrue(SummaryPlanner.fits(SummaryCall(Prompt.SUMMARY_MAP, SummaryPlanner.partText(1, 9, "14:00", "14:00", p), 250)))
    }

    @Test fun threeHoursGoHierarchical() {
        val p = plan(180, english)
        assertTrue(p.parts.toString(), p.parts > SummaryPlanner.FAN_IN)
        assertEquals(2, p.levels)
        assertEquals(1, SummaryPlanner.levels(12))
        assertEquals(2, SummaryPlanner.levels(13))
        assertEquals(3, SummaryPlanner.levels(145))
        // The first reduce level: notes of up to 250 tokens each, at most 12 a call, every call fitting.
        val note = "- [14:03] Marie will call the garage tomorrow at 8 about the brakes; the dentist is on Thursday at 2. ".repeat(9)
        val notes = (1..p.parts).map { k -> SummaryPlanner.noteLabel(k, k, "14:00", "14:10") + "\n" + note }
        val groups = SummaryPlanner.reduceGroups(notes, Prompt.SUMMARY_REDUCE)
        assertTrue(groups.size >= 2)
        assertEquals((0 until p.parts).toList(), groups.flatten())
        for (g in groups) {
            assertTrue(g.size <= SummaryPlanner.FAN_IN)
            val call = SummaryPlanner.reduceCall(g.map { notes[it] }, "en")
            assertTrue(SummaryPlanner.fits(call))
            assertEquals(Prompt.SUMMARY_REDUCE, call.system)
        }
        // The next level merges the merged summaries in one call.
        val merged = groups.map { g -> SummaryPlanner.noteLabel(g.first() + 1, g.last() + 1, "14:00", "17:00") + "\n" + note.take(1_400) }
        assertEquals(1, SummaryPlanner.reduceGroups(merged, Prompt.SUMMARY_REDUCE).size)
        // A note too big for a call on its own is cut to fit.
        val huge = SummaryPlanner.reduceCall(listOf("x ".repeat(20_000)), "fr")
        assertTrue(SummaryPlanner.fits(huge))
        assertEquals(Prompt.SUMMARY_REDUCE_FR, huge.system)
    }

    @Test fun whenTheStrongerAiIsOfferedFirst() {
        val s = RobotSettings()
        assertTrue(!SummaryOffer.helperFirst(s, 3, important = false, flagged = false))
        assertTrue(SummaryOffer.helperFirst(s, 4, important = false, flagged = false))
        assertTrue(SummaryOffer.helperFirst(s, 1, important = true, flagged = false))
        assertTrue(SummaryOffer.helperFirst(s, 1, important = false, flagged = true))
        assertTrue(!SummaryOffer.helperFirst(s.copy(convAutoOfferHelper = false), 9, important = true, flagged = true))
    }

    @Test fun theSummarysLabel() {
        val robot = SummaryRecord("robot", "gemma-4-e2b", SummaryRecord.VIA_PHONE, start, "- [14:03] …", parts = 3)
        assertEquals(robot, SummaryRecord.fromJson(robot.toJson()))
        assertEquals("Robot's summary — made on this phone by Gemma 4 E2B. It can miss or mix up details; check the transcript.", robot.label(zone))
        val claude = SummaryRecord("claude-opus-5-5", "claude-opus-5-5", SummaryRecord.VIA_API, start, "Overview…", consent = true,
            sentAt = ZonedDateTime.of(2026, 10, 11, 14, 52, 0, 0, zone).toInstant().toEpochMilli())
        assertEquals("Claude's summary — sent to Anthropic with your OK on 11 Oct, 14:52.", claude.label(zone))
    }
}
