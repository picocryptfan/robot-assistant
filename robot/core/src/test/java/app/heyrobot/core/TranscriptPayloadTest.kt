package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** "Summarize with a stronger AI": what the card lists is what is sent, and nothing is sent without your tap (docs/conversation-mode.md 7.2). */
class TranscriptPayloadTest {
    private val zone = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 10, 11, 14, 52, 0, 0, zone)
    private val start = ZonedDateTime.of(2026, 10, 11, 14, 2, 0, 0, zone).toInstant().toEpochMilli()
    private val meta = TranscriptMeta("20261011-1402-7f3a", "Lunch", start, start + 41 * 60_000L, SessionStatus.ENDED)
    private val t = TranscriptFold().apply {
        apply(TranscriptEvent.Line(0, 10.0, 14.0, "S1", "en", "So Marie, are we still on for Friday?"))
        apply(TranscriptEvent.Line(1, 70.0, 75.0, "S2", "fr", "Oui, vendredi à 18 h chez Julien. Marie apporte le dessert."))
        apply(TranscriptEvent.Me("S1"))
        apply(TranscriptEvent.Rename("S2", "Marie"))
    }.result()
    private val api = Disclosure.claudeApi(AnthropicModel.OPUS, HelperCost.estimate(AnthropicModel.OPUS, 36_000))

    private fun card(anonymize: Boolean = false) = ConsentCard.build("claude-api", "Claude", api, meta, t, now, "CA", "en", zone, anonymize)

    @Test fun theCardSaysWhatGoesAndWhereAndAtWhatCost() {
        val c = card()
        assertEquals("Send this conversation to Claude?", c.title)
        assertEquals("41 min · 2 speakers · 19 words · English and French.", c.stats)
        assertEquals("It includes what other people said and the names you gave them.", c.includes)
        assertEquals("Goes to: Anthropic (Claude Opus 5.5), over the internet, with your API key. ≈ 9¢.", c.disclosure.line)
        assertEquals("Goes to: the Claude app, with your claude.ai account, when you press Send there.", Disclosure.claudeApp().line)
        assertEquals("Replace names with “Speaker 1, 2”", ConsentCard.TOGGLE)
    }

    @Test fun thePayloadIsWhatTheCardLists() {
        val c = card()
        assertEquals(listOf(
            "[Today is Sunday, 11 October 2026. The user is in Canada.]",
            "[Conversation of 11 October 2026, 41 minutes. Speakers: S1 = the user, S2 = Marie.]",
            "[14:02] S1 (en): So Marie, are we still on for Friday?\n[14:03] S2 (fr): Oui, vendredi à 18 h chez Julien. Marie apporte le dessert.",
            "Summarize this conversation in English.",
        ), c.blocks.map { it.text })
        // Every line of the transcript, and nothing else of it: the words the card counts.
        val sent = c.blocks[2].text
        for (l in t.lines) assertTrue(sent.contains(l.text))
        assertEquals(c.words, Rehear.words(sent.lines().joinToString(" ") { it.substringAfter("): ") }))
        assertEquals(2, c.speakers)
        // The app gets the request first, then the same conversation.
        assertEquals("Summarize this conversation in English.\n\n" + c.blocks[1].text + "\n" + c.blocks[2].text, c.appText)
        assertEquals(c.appText, ConsentCard.appShare(c))
        // In French when Robot's summaries are.
        assertEquals("Summarize this conversation in French.", ConsentCard.build("claude-api", "Claude", api, meta, t, now, "CA", "fr", zone).blocks.last().text)
    }

    @Test fun theNamesToggle() {
        val c = card().withNames(replace = true)
        assertTrue(c.anonymized)
        assertEquals("It includes what other people said.", c.includes)
        val all = c.blocks.joinToString("\n") { it.text } + c.appText
        assertFalse(all, all.contains("Marie"))
        assertTrue(all.contains("Speaker 1 = the user, Speaker 2."))
        assertTrue(all.contains("[14:03] Speaker 2 (fr): Oui, vendredi à 18 h chez Julien. Speaker 2 apporte le dessert."))
        assertTrue(all.contains("So Speaker 2, are we"))
        // Names you didn't give (Julien) are what people said: they stay, as the card says.
        assertTrue(all.contains("Julien"))
        // And back.
        assertEquals(card().blocks, c.withNames(replace = false).blocks)
        assertTrue(c.withNames(replace = true) === c)
    }

    @Test fun noPathSendsWithoutYourTap() {
        // A job can't be made except from a card's Send: no public constructor.
        for (k in SummaryJob::class.java.declaredConstructors) assertTrue(k.toString(), Modifier.isPrivate(k.modifiers) || k.isSynthetic)
        assertFalse(HelperGate.allowed(null))
        val job = card().approve(now.toInstant().toEpochMilli())
        assertTrue(HelperGate.allowed(job))
        assertTrue(job.consent)
        assertEquals(card().blocks, job.blocks)
        assertEquals("20261011-1402-7f3a", job.transcriptId)
        // Too long for the Claude app: the card offers export instead.
        val long = TranscriptFold().apply {
            for (i in 0 until 2_000) apply(TranscriptEvent.Line(i, i * 5.0, i * 5.0 + 4, "S1", "en", "This is a fairly long line of talk to fill the transcript up."))
        }.result()
        assertNull(ConsentCard.appShare(ConsentCard.build("claude-app", "the Claude app", Disclosure.claudeApp(), meta, long, now, "CA", "en", zone)))
        // The API takes it in one request up to the helper's limit (by the card's own estimate); past it, export instead.
        val longCard = ConsentCard.build("claude-api", "Claude", api, meta, long, now, "CA", "en", zone)
        assertTrue(ConsentCard.apiFits(longCard.blocks, 150_000))
        assertFalse(ConsentCard.apiFits(longCard.blocks, 20_000))
        assertTrue(ConsentCard.apiFits(card().blocks, 1_000))
    }

    @Test fun theCostEstimate() {
        // About 6,000 words (36,000 characters): about 9 cents on Opus.
        assertEquals("≈ 9¢", CostText.line(HelperCost.estimate(AnthropicModel.OPUS, 36_000)))
        assertTrue(HelperCost.estimate(AnthropicModel.HAIKU, 36_000) < HelperCost.estimate(AnthropicModel.SONNET, 36_000))
    }
}
