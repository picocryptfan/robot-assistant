// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.ConversationStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ReplyLangChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Conversation mode's words in Québec French (1.1, the l10n design's WP5): a transcript's title and export, your own lines,
 * a summary's labels, its consent card and why it stopped, the Transcription check's results, the fast engine's name,
 * problems and speed test, and the voice commands' language. The English of each is the tests beside this one (unchanged);
 * here the French, with the OQLF's spaces (U+00A0 before ":" and inside « », U+202F before "?" and ";"), and what a model
 * or a helper reads staying English.
 */
class ConversationFrenchTest {
    private val fr = Langs.of(Lang.FR)
    private fun Phrase.fr() = render(fr)
    private val nb = " "
    private val fine = " "
    private val utc: ZoneId = ZoneOffset.UTC

    private val start = ZonedDateTime.of(2026, 10, 11, 14, 2, 0, 0, utc).toInstant().toEpochMilli()

    @Test fun aTranscriptsTitleLengthAndYourOwnLines() {
        val meta = TranscriptMeta("20261011-1402-00b1", "", start)
        assertEquals("Conversation · 11 Oct, 14:02", TranscriptTitles.shown(meta, utc))
        assertEquals("Conversation · 11 octobre, 14${nb}h${nb}02", TranscriptTitles.shown(meta, utc, fr))
        assertEquals("Lunch", TranscriptTitles.shown(meta.copy(title = "Lunch"), utc, fr))
        val first = ZonedDateTime.of(2026, 10, 1, 9, 5, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals("Conversation · 1 Oct, 09:05", TranscriptTitles.shown(meta.copy(createdAt = first), utc))
        assertEquals("Conversation · 1er octobre, 9${nb}h${nb}05", TranscriptTitles.shown(meta.copy(createdAt = first), utc, fr))
        assertEquals("moins d’une minute", TranscriptTitles.durationText(30_000).fr())
        assertEquals("12${nb}min", TranscriptTitles.durationText(12 * 60_000L).fr())
        assertEquals("1${nb}h${nb}05", TranscriptTitles.durationText(65 * 60_000L).fr())
        assertEquals("under a minute", TranscriptTitles.duration(30_000))
        // Your own lines: "You", « Moi »; a name you gave stays as it is.
        val t = Transcript(names = mapOf("S2" to "Marie"), me = "S1")
        assertEquals("You", t.nameOf("S1"))
        assertEquals("Moi", t.nameText("S1").fr())
        assertEquals("Marie", t.nameText("S2").fr())
        assertEquals("S3", t.nameText("S3").fr())
        assertEquals("français ↔ anglais", LangPair.FR_EN.titleText.fr())
        assertEquals("French ↔ English", LangPair.FR_EN.title)
    }

    @Test fun anExportInTheMenusLanguage() {
        val t = TranscriptFold().apply {
            apply(TranscriptEvent.Line(0, 3.0, 5.5, "S1", "fr", "On se reprend-tu demain?"))
            apply(TranscriptEvent.Edit(0, "On se reprend-tu demain matin?"))
            apply(TranscriptEvent.Translation(0, "en", TranscriptEvent.BY_BERGAMOT, "Shall we meet again tomorrow morning?"))
            apply(TranscriptEvent.Gap(10.0, 70.0, TranscriptEvent.GAP_PAUSE))
            apply(TranscriptEvent.Line(1, 71.0, 73.0, "Me", "en", "Sure."))
        }.result()
        val meta = TranscriptMeta("20261011-1402-00b1", "", start, start + 80_000)
        val shown = meta.copy(title = TranscriptTitles.shown(meta, utc, fr))
        assertEquals("""# Conversation · 11 octobre, 14${nb}h${nb}02

- Date$nb: 11 octobre 2026, 14:02–14:03 (1${nb}min)
- Intervenants$nb: S1, Moi
- Langues$nb: français, anglais
- Transcrit sur ce téléphone par Robot (Parakeet v3). Traductions$nb: le modèle de Robot / Bergamot (marquées «${nb}rapide$nb»).

**[14:02:03] S1 (FR):** On se reprend-tu demain matin? _(modifiée)_
> EN: Shall we meet again tomorrow morning? _(rapide)_

*\[en pause 14:02–14:03\]*

**[14:03:11] Moi (EN):** Sure.
""", TranscriptExport.markdown(shown, t, ExportOptions(), utc, fr))
        val txt = TranscriptExport.text(shown, t, ExportOptions(times = false), utc, fr)
        assertTrue(txt, txt.contains("\nS1 (FR): On se reprend-tu demain matin? (modifiée)\n    EN: Shall we meet again tomorrow morning? (rapide)\n"))
        assertTrue(txt, txt.contains("\n[en pause 14:02–14:03]\n"))
        assertEquals("Transcription 2026-10-11 1402.md", TranscriptExport.fileName(meta, "md", utc, fr))
        assertEquals("Transcript 2026-10-11 1402.txt", TranscriptExport.fileName(meta, "txt", utc))
        assertEquals("[micro indisponible 14:02–14:03]", TranscriptExport.gapPhrase(meta, TranscriptGap(10.0, 70.0, TranscriptEvent.GAP_MIC), utc).fr())
        // English is 1.0's: "You" for your voice, "edited" and "fast".
        val en = TranscriptExport.markdown(meta.copy(title = TranscriptTitles.shown(meta, utc)), t, ExportOptions(), utc)
        assertTrue(en, en.contains("- Speakers: S1, You\n- Languages: French, English\n"))
        assertTrue(en, en.contains("demain matin? _(edited)_\n> EN: Shall we meet again tomorrow morning? _(fast)_\n"))
        assertTrue(en, en.contains("Robot (Parakeet v3). Translations: Robot's model / Bergamot (marked \"fast\").\n"))
    }

    private val card: ConsentCard by lazy {
        val t = TranscriptFold().apply {
            apply(TranscriptEvent.Line(0, 10.0, 14.0, "S1", "en", "So Marie, are we still on for Friday?"))
            apply(TranscriptEvent.Line(1, 70.0, 75.0, "S2", "fr", "Oui, vendredi à 18 h chez Julien. Marie apporte le dessert."))
            apply(TranscriptEvent.Me("S1"))
            apply(TranscriptEvent.Rename("S2", "Marie"))
        }.result()
        val meta = TranscriptMeta("20261011-1402-7f3a", "Lunch", start, start + 41 * 60_000L, SessionStatus.ENDED)
        val api = Disclosure.claudeApi(AnthropicModel.OPUS, HelperCost.estimate(AnthropicModel.OPUS, 36_000))
        ConsentCard.build("claude-api", "Claude", api, meta, t, ZonedDateTime.of(2026, 10, 11, 14, 52, 0, 0, utc), "CA", "fr", utc)
    }

    @Test fun theConsentCardInFrenchWhileClaudeReadsEnglish() {
        assertEquals("Envoyer cette conversation à Claude$fine?", card.titleText.fr())
        assertEquals("41${nb}min · 2 intervenants · 19 mots · anglais et français.", card.statsText.fr())
        assertEquals("Elle contient ce que les autres personnes ont dit et les noms que tu leur as donnés.", card.includesText.fr())
        assertEquals("Destination$nb: Anthropic (Claude Opus 5.5), par Internet, avec ta clé d’API. ≈ 9¢.", card.disclosure.lineText.fr())
        assertEquals("Destination$nb: l’appli Claude, avec ton compte claude.ai, quand tu touches Envoyer dans l’appli.",
            Disclosure.claudeApp().lineText.fr())
        assertEquals("Remplacer les noms par «${nb}Speaker 1, 2$nb»", ConsentCard.toggle.fr())
        assertEquals("Elle contient ce que les autres personnes ont dit.", card.withNames(true).includesText.fr())
        val app = ConsentCard.build("claude-app", ConversationStrings.theClaudeApp().en(), Disclosure.claudeApp(), TranscriptMeta("x", "", start),
            Transcript(), ZonedDateTime.of(2026, 10, 11, 14, 52, 0, 0, utc), "CA", "en", utc, toText = ConversationStrings.theClaudeApp())
        assertEquals("Envoyer cette conversation à l’appli Claude$fine?", app.titleText.fr())
        assertEquals("Send this conversation to the Claude app?", app.title)
        assertEquals("Trop long pour une seule requête$fine; exporte-la et joins le fichier.", ConsentCard.tooLongForApi.fr())
        // What Claude reads stays English, whatever the card shows.
        assertEquals("Summarize this conversation in French.", card.blocks.last().text)
        assertEquals("[Conversation of 11 October 2026, 41 minutes. Speakers: S1 = the user, S2 = Marie.]", card.blocks[1].text)
    }

    @Test fun aSummarysLabelsAndItsCheck() {
        val sent = ZonedDateTime.of(2026, 10, 11, 14, 52, 0, 0, utc).toInstant().toEpochMilli()
        val claude = SummaryRecord("claude-opus-5-5", "claude-opus-5-5", SummaryRecord.VIA_API, sent, "…", consent = true, sentAt = sent)
        assertEquals("Claude's summary — sent to Anthropic with your OK on 11 Oct, 14:52.", claude.label(utc))
        assertEquals("Résumé de Claude — envoyé à Anthropic avec ton accord le 11 octobre à 14${nb}h${nb}52.", claude.label(utc, fr))
        assertEquals("Résumé de Robot — fait sur ce téléphone par ton propre fichier de modèle. Il peut oublier ou mélanger des détails$fine; vérifie la transcription.",
            SummaryRecord.robotLabelText(BrainModel.OWN.id).fr())
        assertEquals("Résumé de Robot — fait sur ce téléphone par le modèle de Robot. Il peut oublier ou mélanger des détails$fine; vérifie la transcription.",
            SummaryRecord("robot", "nope", SummaryRecord.VIA_PHONE, sent, "…").label(utc, fr))
        assertEquals("Robot n’a pas trouvé «${nb}17 h$nb» dans la transcription.", SummaryMiss("17 h", SummaryMiss.Kind.NUMBER).messageText.fr())
        assertEquals("Robot n’a trouvé aucune ligne à 14:04.", SummaryMiss("14:04", SummaryMiss.Kind.TIME).messageText.fr())
        assertEquals("Cette conversation est longue$fine; une IA plus puissante la résumera plus fidèlement.", SummaryOffer.card.fr())
        // Why a summary stopped: the screen's words, the same Outcome as before.
        val failed = LocalSummary.Outcome.Failed(ConversationStrings.modelProblem(), 2)
        assertEquals(LocalSummary.Outcome.Failed("Robot's model ran into a problem.", 2), failed)
        assertEquals("Le modèle de Robot a eu un problème.", failed.whyText.fr())
        assertEquals("Robot's model isn't ready. Open the assistant once, or check Settings › Model.",
            ConversationStrings.modelNotReadyOpen("page" to app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.model)).en())
        assertEquals("Le modèle de Robot n’est pas prêt. Ouvre l’assistant une fois, ou vérifie Réglages › Modèle.",
            ConversationStrings.modelNotReadyOpen("page" to app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.model)).fr())
        assertEquals("Le modèle de Robot a eu un problème. Ce qui a été fait est gardé$nb: réessaie pour continuer.",
            ConversationStrings.failedKept("why" to failed.whyText).fr())
        val reason = HelperResult.Failed(ConversationStrings.helperDeclined("name" to "Claude"))
        assertEquals(HelperResult.Failed("Claude declined to summarize this conversation."), reason)
        assertEquals("Claude a refusé de résumer cette conversation.", reason.reasonText.fr())
    }

    private fun line(lang: String, ref: String, heard: String, reader: Int = 1, mic: CheckMic = CheckMic.PHONE) =
        CheckLine(CheckMode.READ, lang, ref, heard, lang, 400, 3_000, reader, mic)

    @Test fun theTranscriptionChecksResults() {
        val fr = (1..20).map { k -> line("fr", "on se reprend tu demain matin chez nous", if (k <= 2) "on se reprend demain matin chez nous" else "on se reprend tu demain matin chez nous", reader = if (k <= 10) 1 else 2) }
        val f = CheckReport.totals(fr).single()
        assertEquals("French (Québec): 1% of words wrong over 20 lines, 2 readers. Pass mark 15%: passes.", CheckReport.summary(f))
        assertEquals("français (Québec)$nb: 1$nb% de mots erronés sur 20 lignes, 2 lecteurs. Seuil de réussite 15$nb%$nb: réussi.", CheckReport.summaryText(f).fr())
        val few = CheckReport.totals(fr.takeLast(3).map { it.copy(reader = 1, mic = CheckMic.HEADSET) }).single()
        assertEquals("French (Québec), headset mic: 0% of words wrong over 3 lines, 1 reader. Pass mark 15%: under the mark so far. " +
            "The gate needs 20 lines from 2 readers.", CheckReport.summary(few))
        assertEquals("français (Québec), micro des écouteurs$nb: 0$nb% de mots erronés sur 3 lignes, 1 lecteur. Seuil de réussite 15$nb%$nb: " +
            "sous le seuil jusqu’ici. Le test demande 20 lignes de 2 lecteurs.", CheckReport.summaryText(few).fr())
        val l = line("fr", "on se reprend tu demain matin chez nous", "on se reprend demain matin chez nous", mic = CheckMic.HEADSET)
        assertEquals("1. FR read · reader 1 · headset mic · 1 of 8 words wrong (13%) · heard as FR · decoded in 400 ms · 3.0 s", CheckReport.line(1, l))
        assertEquals("1. FR lu · lecteur 1 · micro des écouteurs · 1 mot erroné sur 8 (13$nb%) · entendu comme FR · décodé en 400${nb}ms · 3,0${nb}s",
            CheckReport.lineText(1, l).fr())
        // Clips refused, with the reason in the menus' language.
        val none = CheckClips.pair(emptyList(), "x") as CheckClips.Pairing.Refused
        assertEquals("No clips picked.", none.why)
        assertEquals("Aucun extrait choisi.", none.whyText.fr())
        val two = CheckClips.pair(listOf("a.wav", "b.wav"), "one line only") as CheckClips.Pairing.Refused
        assertEquals("2 clips but 1 line: the text file needs one line per clip, in the order of the clips' names.", two.why)
        assertEquals("2 extraits, mais 1 ligne$nb: le fichier texte doit avoir une ligne par extrait, dans l’ordre des noms des extraits.", two.whyText.fr())
        val missing = CheckClips.pair(listOf("a.wav", "b.wav"), "a\tbonjour\nc\tsalut") as CheckClips.Pairing.Refused
        assertEquals("1 clip has no line in the text file.", missing.why)
        assertEquals("1 extrait n’a pas de ligne dans le fichier texte.", missing.whyText.fr())
    }

    @Test fun theFastEnginesNameProblemsAndSpeedTest() {
        val b = MtBundle.FR_EN
        assertEquals("${b.title} (${VoiceModelLogic.size(b.downloadBytes)})", MtModelLogic.label(b))
        assertEquals("Fast translation engine (52 MB)", MtModelLogic.label(b))
        assertEquals("Moteur de traduction rapide (52${nb}Mo)", MtModelLogic.labelText(b).fr())
        for (n in listOf(0L, 499_999L, 9_600_000L, 40_257_283L, 999_999_999L, 1_000_000_000L, 1_234_567_890L, 12_345_678_901L)) {
            assertEquals("$n", VoiceModelLogic.size(n), MtModelLogic.sizeText(n).en())
        }
        assertEquals("1,23${nb}Go", MtModelLogic.sizeText(1_234_567_890L).fr())
        assertEquals("Download the fast translation engine first (Settings › Conversation).", MtError.NOT_DOWNLOADED.text())
        assertEquals("Télécharge d’abord le moteur de traduction rapide (Réglages › Conversation).", MtError.NOT_DOWNLOADED.textPhrase().fr())
        assertEquals("Arrêté.", MtError.CANCELLED.textPhrase().fr())
        val set = listOf(MtTestLine("fr-en", "Bonjour.", "Hello.", "Hello."), MtTestLine("en-fr", "Hello.", "Bonjour.", "Bonjour."))
        val r = MtSpeedReport.of(set, listOf("Hello.", "Salut."), listOf(30L, 100L), mapOf("fr-en" to 174L), MtMemory(290 * 1024L, 300 * 1024L), 1_000)
        val lines = r.summaryLines(set.size)
        assertEquals(listOf(true, false, true, false), lines.map { it.missed })
        assertEquals(listOf(
            "Speed: median 65 ms, slowest 100 ms a line (the mark is 60 ms or less): misses.",
            "Memory: 300 MB at most, 290 MB now with both directions loaded (the mark is 450 MB or less): passes.",
            "Translations: 1 of 2 the same as on the build machine (the mark is all of them): misses.",
            "Loaded in 174 ms (fr-en).",
        ), r.summary(set.size))
        assertEquals(listOf(
            "Vitesse$nb: médiane de 65${nb}ms, ligne la plus lente 100${nb}ms (seuil$nb: 60${nb}ms ou moins)$nb: échoué.",
            "Mémoire$nb: 300${nb}Mo au plus, 290${nb}Mo maintenant avec les deux directions chargées (seuil$nb: 450${nb}Mo ou moins)$nb: réussi.",
            "Traductions$nb: 1 sur 2 identiques à celles de la machine de compilation (seuil$nb: toutes)$nb: échoué.",
            "Chargé en 174${nb}ms (fr-en).",
        ), lines.map { it.text.fr() })
        assertEquals("Le moteur de traduction rapide n’a pas répondu à temps.", MtSpeedReport.failed(MtError.TIMEOUT, 5).summaryLines(2).single().text.fr())
        for (ms in listOf(0L, 4_999L, 5_000L, 59_999L, 60_000L, 3_599_999L, 3_600_000L, 86_399_999L, 86_400_000L, 500_000_000L)) {
            assertEquals("$ms", VoiceLogText.ago(ms), MtSpeedReport.agoText(ms).en())
        }
        assertEquals("il y a 5${nb}min", MtSpeedReport.agoText(5 * 60_000L).fr())
        assertEquals("à l’instant", MtSpeedReport.agoText(1_000).fr())
    }

    @Test fun theInterpretersHalvesSayItInTheirOwnLanguage() {
        assertEquals("Parle$nb: Robot traduit.", ConversationStrings.speakRobotTranslates().fr())
        assertEquals("Speak: Robot translates.", ConversationStrings.speakRobotTranslates().en())
        assertEquals("Robot t’a entendu", ConversationStrings.heardYou().fr())
        assertEquals("Parler en français", ConversationStrings.speakThisLanguage().fr())
        assertEquals("Speak English", ConversationStrings.speakThisLanguage().en())
    }

    @Test fun theVoiceCommandsSayWhichLanguageMatched() {
        assertEquals(Lang.EN, ConversationRouter.lang("transcribe this conversation", running = false))
        assertEquals(Lang.FR, ConversationRouter.lang("transcris cette conversation", running = false))
        assertEquals(Lang.FR, ConversationRouter.lang("Dis Robot, transcris la conversation s'il te plaît", running = false))
        assertEquals(Lang.EN, ConversationRouter.lang("hey robot, be my interpreter", running = false))
        assertEquals(Lang.FR, ConversationRouter.lang("sois mon interprète", running = false))
        assertEquals(Lang.FR, ConversationRouter.lang("arrête de transcrire", running = true))
        // « reprends la transcription » resumes a paused session, and says it in French.
        assertEquals(ConversationCommand.Resume, ConversationRouter.route("reprends la transcription", running = true))
        assertEquals(Lang.FR, ConversationRouter.lang("reprends la transcription", running = true))
        assertEquals(ConversationCommand.Summarize, ConversationRouter.route("résume cette conversation", running = true))
        assertEquals(Lang.FR, ConversationRouter.lang("résume cette conversation", running = true))
        // One or two words aren't sure ("pause", "continue" are both languages'); no command, no language.
        assertEquals(ConversationCommand.Pause, ConversationRouter.route("pause", running = true))
        assertNull(ConversationRouter.lang("pause", running = true))
        assertNull(ConversationRouter.lang("what's the weather", running = false))
        assertNull(ConversationRouter.lang("stop transcribing", running = false))
    }

    @Test fun yourLanguageFollowsTheReplyLanguageSetting() {
        assertEquals("fr", LangLabel.yours(Lang.FR, ReplyLangChoice.SPOKEN))
        assertEquals("en", LangLabel.yours(Lang.EN, ReplyLangChoice.SPOKEN))
        assertEquals("en", LangLabel.yours(Lang.FR, ReplyLangChoice.EN))
        assertEquals("fr", LangLabel.yours(Lang.EN, ReplyLangChoice.FR))
        assertEquals("fr", LangLabel.yours(Lang.FR, ReplyLangChoice.MENUS))
    }
}
