package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Export to Markdown and plain text (golden files), and search (docs/conversation-mode.md 6.3, 6.5). */
class TranscriptExportTest {
    private val zone = ZoneOffset.UTC
    private val start = ZonedDateTime.of(2026, 10, 11, 14, 2, 0, 0, zone).toInstant().toEpochMilli()
    private val meta = TranscriptMeta("20261011-1402-7f3a", "Lunch with Marie", start, start + 41 * 60_000L, SessionStatus.ENDED)

    private val transcript: Transcript = TranscriptFold().apply {
        apply(TranscriptEvent.Line(0, 11.0, 14.5, "S1", "fr", "On se reprend-tu demain? J'ai pogné un *char* de trafic."))
        apply(TranscriptEvent.Translation(0, "en", TranscriptEvent.BY_BERGAMOT, "Shall we meet again tomorrow? I got stuck in traffic."))
        apply(TranscriptEvent.Line(1, 15.2, 16.0, "S2", "en", "Sure, at 5?"))
        apply(TranscriptEvent.Translation(1, "fr", TranscriptEvent.BY_GEMMA, "Bien sûr, à 17 h?"))
        apply(TranscriptEvent.Line(2, 17.0, 17.4, "S1", "fr", "Oui.", prior = true))
        apply(TranscriptEvent.Line(3, 18.0, 19.0, "S2", "en", "This line was deleted."))
        apply(TranscriptEvent.Delete(3))
        apply(TranscriptEvent.Gap(60.0, 300.0, TranscriptEvent.GAP_MIC))
        apply(TranscriptEvent.Line(4, 301.0, 303.0, "S2", "en", "Sorry, my phone rang. Where were we?"))
        apply(TranscriptEvent.Edit(4, "Sorry, a call came in. Where were we?"))
        apply(TranscriptEvent.Rename("S1", "Marie"))
        apply(TranscriptEvent.Me("S2"))
    }.result()

    @Test fun markdownGolden() {
        assertEquals("""
            # Lunch with Marie

            - Date: 11 October 2026, 14:02–14:43 (41 min)
            - Speakers: Marie (S1), You (S2)
            - Languages: French, English
            - Transcribed on this phone by Robot (Parakeet v3). Translations: Gemma 4 E2B / Bergamot (marked "fast").

            **[14:02:11] Marie (FR):** On se reprend-tu demain? J'ai pogné un \*char\* de trafic.
            > EN: Shall we meet again tomorrow? I got stuck in traffic. _(fast)_

            **[14:02:15] You (EN):** Sure, at 5?
            > FR: Bien sûr, à 17 h?

            **[14:02:17] Marie (FR?):** Oui.

            *\[microphone unavailable 14:03–14:07\]*

            **[14:07:01] You (EN):** Sorry, a call came in. Where were we? _(edited)_

        """.trimIndent(), TranscriptExport.markdown(meta, transcript, zone = zone))
    }

    @Test fun plainTextGolden() {
        assertEquals("""
            Lunch with Marie
            Date: 11 October 2026, 14:02–14:43 (41 min)
            Speakers: Marie (S1), You (S2)
            Languages: French, English
            Transcribed on this phone by Robot (Parakeet v3). Translations: Gemma 4 E2B / Bergamot (marked "fast").

            [14:02:11] Marie (FR): On se reprend-tu demain? J'ai pogné un *char* de trafic.
                EN: Shall we meet again tomorrow? I got stuck in traffic. (fast)

            [14:02:15] You (EN): Sure, at 5?
                FR: Bien sûr, à 17 h?

            [14:02:17] Marie (FR?): Oui.

            [microphone unavailable 14:03–14:07]

            [14:07:01] You (EN): Sorry, a call came in. Where were we? (edited)

        """.trimIndent(), TranscriptExport.text(meta, transcript, zone = zone))
    }

    @Test fun theOptions() {
        val bare = TranscriptExport.text(meta, transcript, ExportOptions(translations = false, times = false, names = false), zone)
        assertTrue(bare, bare.contains("\nS1 (FR): On se reprend-tu demain?"))
        assertTrue(bare.contains("Speakers: S1, S2\n"))
        assertTrue(!bare.contains("EN: Shall") && !bare.contains("Translations:"))
        assertTrue(!bare.contains("[14:02"))
        assertEquals("Transcript 2026-10-11 1402.md", TranscriptExport.fileName(meta, "md", zone))
        // Nothing a person said can become a heading, a link or HTML in the Markdown.
        assertEquals("\\# Title \\[x\\](y) \\<b\\> a \\| b", TranscriptExport.md("# Title [x](y) <b>\na | b"))
    }

    /** Phase 5.3: the names you gave go into the export, after merges and splits, and an enrolled voice is "You". */
    @Test fun exportsUseTheNamesAfterMergesAndSplits() {
        val t = TranscriptFold().apply {
            apply(TranscriptEvent.Line(0, 1.0, 3.0, "Me", "en", "Shall we start?"))
            apply(TranscriptEvent.Line(1, 4.0, 6.0, "S1", "fr", "Oui, on commence. Moi je prends le café."))
            apply(TranscriptEvent.Line(2, 7.0, 9.0, "S2", "fr", "Et moi un thé."))
            apply(TranscriptEvent.Merge("S2", "S1"))
            apply(TranscriptEvent.Rename("S1", "Marie"))
            apply(TranscriptEvent.Split(1, 18, 3, "S3"))
            apply(TranscriptEvent.Rename("S3", "Luc"))
        }.result()
        val text = TranscriptExport.text(meta, t, ExportOptions(times = false), zone)
        assertTrue(text, text.contains("Speakers: You, Marie (S1), Luc (S3)\n"))
        assertTrue(text, text.contains("\nYou (EN): Shall we start?\n"))
        assertTrue(text, text.contains("\nMarie (FR): Oui, on commence.\n"))
        assertTrue(text, text.contains("\nLuc (FR): Moi je prends le café.\n"))
        assertTrue(text, text.contains("\nMarie (FR): Et moi un thé.\n"))
        // Without names: the labels.
        assertTrue(TranscriptExport.text(meta, t, ExportOptions(names = false), zone).contains("Speakers: Me, S1, S3\n"))
    }

    @Test fun searchIgnoresAccentsAndCaseAndFindsTranslations() {
        val other = meta.copy(id = "20261012-0900-0001", createdAt = start + 86_400_000L, title = "Dentist")
        val t2 = TranscriptFold().apply(TranscriptEvent.Line(0, 1.0, 2.0, "S1", "fr", "Le rendez-vous chez le dentiste est à neuf heures et demie, pas à dix heures.")).result()
        val all = listOf(meta to transcript, other to t2)
        assertEquals(listOf(TranscriptHit("20261011-1402-7f3a", 0, "On se reprend-tu demain? J'ai pogné un *char* de trafic.")),
            TranscriptIndex.search("POGNE", all))
        // In a translation, newest transcript first.
        assertEquals(listOf("20261011-1402-7f3a" to 0), TranscriptIndex.search("stuck in", all).map { it.transcriptId to it.line })
        assertEquals(listOf("20261012-0900-0001", "20261011-1402-7f3a"), TranscriptIndex.search("a", all, limit = 10).map { it.transcriptId }.distinct())
        assertEquals(1, TranscriptIndex.search("sur", all, limit = 1).size)
        // A long line is cut around the match.
        val hit = TranscriptIndex.search("Dentiste", all).single()
        assertTrue(hit.snippet, hit.snippet.contains("dentiste"))
        assertEquals(emptyList<TranscriptHit>(), TranscriptIndex.search("  ", all))
        val cut = TranscriptIndex.snippet("a".repeat(100) + " bcd " + "e".repeat(100), "bcd")!!
        assertTrue(cut, cut.startsWith("…") && cut.endsWith("…") && cut.contains(" bcd ") && cut.length <= TranscriptIndex.SNIPPET + 2)
        // œ and the typographic apostrophe fold too.
        assertEquals(listOf(0), TranscriptIndex.search("coeur j'ai", listOf(meta to TranscriptFold().apply(TranscriptEvent.Line(0, 0.0, 1.0, "S1", "fr", "Le cœur j’ai")).result())).map { it.line })
    }

    @Test fun memoryReadsOnlyWhatYouLetItRemember() {
        val kept = meta.copy(id = "20261012-0900-0002", memory = true)
        assertEquals(listOf(kept.id), TranscriptIndex.forMemory(listOf(meta to transcript, kept to transcript)).map { it.first.id })
        // Changes reach a listener until it stops listening.
        val seen = ArrayList<TranscriptChange>()
        val stop = TranscriptEvents.listen { seen += it }
        TranscriptEvents.publish(TranscriptChange.Created(kept.id))
        stop()
        TranscriptEvents.publish(TranscriptChange.Deleted(kept.id))
        assertEquals(listOf<TranscriptChange>(TranscriptChange.Created(kept.id)), seen)
    }
}
