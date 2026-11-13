// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lasttime

import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.memory.EmbedRole
import app.heyrobot.core.memory.TextEmbedder
import kotlinx.coroutines.runBlocking
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LastTimeTest {
    private val today = LocalDate.of(2026, 10, 31)
    private val en = Langs.of(Lang.EN)
    private val fr = Langs.of(Lang.FR)
    private fun Phrase.en() = render(en)
    private fun Phrase.fr() = render(fr)

    private val jiffy = LastCandidate(LastSource.RECEIPT, "r1", "Jiffy service", "", LocalDate.of(2026, 10, 10))
    private val dentist = LastCandidate(LastSource.CALENDAR, "c1", "Dentist", "", LocalDate.of(2026, 9, 15))
    private val note = LastCandidate(LastSource.NOTE, "n1", "Bike tune-up", "chain greased, brakes done", LocalDate.of(2026, 10, 30))
    private val books = LastCandidate(LastSource.RECEIPT, "r2", "Bookstore", "two paperbacks", LocalDate.of(2026, 10, 20))

    // Fake vectors: "oil change" and "Jiffy service" point the same way; the rest elsewhere.
    private val oil = floatArrayOf(1f, 0f, 0f)
    private val vectors = mapOf("receipt:r1" to floatArrayOf(0.95f, 0.3f, 0f), "receipt:r2" to floatArrayOf(0f, 0f, 1f),
        "calendar:c1" to floatArrayOf(0f, 1f, 0f), "note:n1" to floatArrayOf(0f, 0.2f, 1f))

    private fun ask(what: String, q: FloatArray?, cs: List<LastCandidate>, v: Map<String, FloatArray> = vectors) =
        LastTime.answer(LastTime.match(what, q, cs, v, today), today)

    @Test fun aReceiptByMeaningNamesItsSourceDateAndAgo() {
        val all = listOf(jiffy, dentist, note, books)
        val hits = LastTime.match("get the oil changed", oil, all, vectors, today)
        assertEquals(listOf("receipt:r1"), hits.map { it.candidate.key })
        val s = LastTime.answer(hits, today).en()
        assertTrue(s, s.startsWith("Last time: ") && "October 10" in s && "3 weeks ago" in s && "From a receipt in Files: Jiffy service." in s)
        // Without the vectors, "oil change" shares no word with "Jiffy service": nothing is made up.
        assertEquals(LastTime.answer(emptyList(), today).en(), ask("get the oil changed", null, all).en())
    }

    @Test fun eachSourceAloneByWords() {
        assertTrue(ask("go to the dentist", null, listOf(dentist)).en().let { "“Dentist” was on your calendar on " in it && "September 15" in it && "1 month" !in it })
        assertTrue(ask("tune up my bike", null, listOf(note)).en().let { "From your notes: Bike tune-up." in it && "yesterday" in it })
        assertTrue(ask("bookstore", null, listOf(books)).en().let { "From a receipt in Files: Bookstore." in it && "11 days ago" in it })
        val log = LastCandidate(LastSource.LOGBOOK, "l1", "took my pills", "", today)
        assertTrue(ask("take my pills", null, listOf(log)).en().let { "From your Logbook: took my pills." in it && "today" in it })
    }

    @Test fun aCalendarPlanIsAPlanAndAReceiptCloseToItIsNamedToo() {
        val plan = LastCandidate(LastSource.CALENDAR, "c2", "Oil change", "", LocalDate.of(2026, 10, 8))
        val v = vectors + ("calendar:c2" to floatArrayOf(1f, 0f, 0f))
        val s = ask("get the oil changed", oil, listOf(jiffy, plan), v).en()
        assertTrue(s, s.startsWith("Last time: ") && "Jiffy service" in s && "Also: “Oil change” was on your calendar on " in s)
        // Alone, the plan never says it happened.
        val alone = ask("oil change", null, listOf(plan)).en()
        assertTrue(alone, alone.startsWith("“Oil change” was on your calendar") && "Last time" !in alone && "you " !in alone)
        // A plan still to come never answers.
        val later = plan.copy(day = today.plusDays(5))
        assertEquals(LastTime.answer(emptyList(), today).en(), ask("oil change", null, listOf(later)).en())
    }

    @Test fun newestWinsAndAFarOlderOneIsNotNamed() {
        val old = jiffy.copy(id = "r0", day = LocalDate.of(2026, 6, 1))
        val v = vectors + ("receipt:r0" to floatArrayOf(1f, 0f, 0f))
        val s = ask("oil change", oil, listOf(old, jiffy), v).en()
        assertTrue(s, "October 10" in s && "Also" !in s)
        assertTrue(LastTime.ago(LocalDate.of(2026, 6, 1), today).en() == "4 months ago")
        assertEquals("2 years ago", LastTime.ago(LocalDate.of(2024, 9, 1), today).en())
    }

    @Test fun noneFoundIsOneLine() {
        val s = ask("go skydiving", null, listOf(jiffy, dentist, note, books)).en()
        assertEquals("I found nothing about that in your receipts, calendar or notes.", s)
        assertFalse(s.contains('\n'))
        assertEquals(s, ask("go skydiving", null, emptyList()).en())
    }

    @Test fun lockedReadsNothingAndNamesNothing() {
        var read = false
        val s = LastTime.respond(true, today) { read = true; listOf(LastHit(jiffy, 1.0)) }.en()
        assertFalse(read)
        assertTrue(s, s.startsWith("Unlock your phone first") && "Jiffy" !in s && "October" !in s)
        assertTrue(LastTime.respond(false, today) { listOf(LastHit(jiffy, 1.0)) }.en().contains("Jiffy"))
    }

    @Test fun french() {
        val s = ask("faire changer l'huile", oil, listOf(jiffy)).fr()
        assertTrue(s, s.startsWith("La dernière fois") && "10 octobre" in s && "il y a 3 semaines" in s && "D’après un reçu dans les Fichiers de Robot" in s)
        assertTrue(ask("dentiste", null, listOf(dentist.copy(title = "Dentiste"))).fr().let { "Dentiste" in it && "était à ton agenda le" in it })
        assertTrue(LastTime.respond(true, today) { emptyList() }.fr().startsWith("Déverrouille ton téléphone"))
        assertTrue(ask("parachute", null, listOf(jiffy)).fr().startsWith("Je n’ai rien trouvé"))
    }

    @Test fun theQuestionsItHears() {
        fun p(q: String) = LastTimeRouter.parse(q)
        assertEquals(LastAsk("get the oil changed", Lang.EN), p("When did I last get the oil changed?"))
        assertEquals("went to the dentist", p("when was the last time I went to the dentist")?.what)
        assertEquals("oil change", p("When was my last oil change?")?.what)
        assertEquals("changed the furnace filter", p("how long since I last changed the furnace filter")?.what)
        assertEquals(LastAsk("fait changer l'huile", Lang.FR), p("Quand est-ce que j’ai fait changer l’huile pour la dernière fois?"))
        assertEquals("allé chez le dentiste", p("C'est quand la dernière fois que je suis allé chez le dentiste ?")?.what)
        assertEquals("changement d'huile", p("C'était quand mon dernier changement d'huile")?.what)
        for (q in listOf("when did I last", "what's the weather", "remind me to change the oil", "when is my next oil change")) assertNull(q, p(q))
    }

    // review-last-time H1: with the model downloaded, an older item behind more than 60 newer ones is still found,
    // by its words before its vector is made and by meaning after; the question as speech gives it, lowercased.
    private val busyMonths = List(61) { LastCandidate(LastSource.CALENDAR, "s$it", "Team standup", "boardroom", today.minusDays(it.toLong() % 50)) }
    private val oilReceipt = LastCandidate(LastSource.RECEIPT, "r9", "Oil change", "Jiffy Lube 5W-30", today.minusMonths(3))
    private val other = floatArrayOf(0f, 1f, 0f)

    private class FakeEmbedder : TextEmbedder {
        val batches = ArrayList<Int>()
        override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray> {
            batches += texts.size
            return texts.map { if ("oil" in it.lowercase() || "jiffy" in it.lowercase()) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f) }
        }
    }

    @Test fun anOlderReceiptBehindSixtyNewerEventsIsFound() {
        val all = busyMonths + oilReceipt
        val q = LastTimeRouter.parse("when did i last get the oil changed")!!
        // Only the 60 newest have vectors (as before the fix): the receipt is still found by its words.
        val newest = all.sortedByDescending { it.day }.take(60).associate { it.key to other }
        val byWords = LastTime.match(q.what, oil, all, newest, today, said = q.said)
        assertEquals(listOf("receipt:r9"), byWords.map { it.candidate.key })
        // Every candidate gets its vector, none capped by recency: the receipt is found by meaning.
        val vs = LastTimeVectors(dim = 3)
        assertEquals(62, runBlocking { vs.refresh(all, FakeEmbedder()) })
        val s = LastTime.answer(LastTime.match("have my car serviced", oil, all, vs.known(all), today), today).en()
        assertTrue(s, "From a receipt in Files: Oil change." in s && "3 months ago" in s)
    }

    // review-last-time M4 (brief item 5): vectors are made once, 8 to a :brain request, stopped between requests.
    @Test fun vectorsAreMadeOnceEightAtATimeAndStopOnTime() {
        val all = busyMonths + oilReceipt
        val vs = LastTimeVectors(dim = 3)
        val e = FakeEmbedder()
        runBlocking { vs.refresh(all, e) }
        assertTrue(e.batches.toString(), e.batches.all { it <= 8 } && e.batches.sum() == 62)
        e.batches.clear()
        assertEquals(0, runBlocking { vs.refresh(all, e) })
        assertTrue(e.batches.isEmpty())
        // An edited one is made again; a deleted one is dropped.
        val edited = all.map { if (it.id == "s3") it.copy(title = "Team retro") else it }.filter { it.id != "s4" }
        assertEquals(60, vs.known(edited).size)
        assertEquals(1, runBlocking { vs.refresh(edited, e) })
        assertEquals(61, vs.size)
        var n = 0
        assertEquals(8, runBlocking { LastTimeVectors(dim = 3).refresh(all, e) { n++ < 1 } })
        assertEquals(8, BrainMsg.Embed.MAX_TEXTS)
        assertNull(BrainMsg.decode(BrainProtocol.REQ_EMBED, BrainMsg.Embed("p", List(9) { "x" }).toJson().toString()))
    }

    // review-last-time-b M1: one question runs at most 8 batches of the rebuild (64 texts); the next question carries on.
    @Test fun aQuestionRunsAtMostEightBatches() {
        val many = List(100) { LastCandidate(LastSource.NOTE, "m$it", "Note $it", "", today.minusDays(it.toLong())) }
        val vs = LastTimeVectors(dim = 3)
        val e = FakeEmbedder()
        assertEquals(64, runBlocking { vs.refresh(many, e) })
        assertEquals(8, e.batches.size)
        assertEquals(36, runBlocking { vs.refresh(many, e) })
        assertEquals(16, runBlocking { LastTimeVectors(dim = 3).refresh(many, e, maxBatches = 2) })
    }

    // review-last-time M1: a strong older match beats a weak newer one; two close ones are both named.
    @Test fun aStrongOlderMatchBeatsAWeakNewerOne() {
        val mechanic = LastCandidate(LastSource.NOTE, "n2", "Find a new mechanic", "", LocalDate.of(2026, 10, 30))
        val v = vectors + ("note:n2" to floatArrayOf(0.62f, 0.7846f, 0f)) + ("receipt:r1" to floatArrayOf(1f, 0f, 0f))
        val s = ask("get the oil changed", oil, listOf(jiffy, mechanic), v).en()
        assertTrue(s, "Jiffy service" in s && "mechanic" !in s)
        val plan = LastCandidate(LastSource.CALENDAR, "c2", "Car at the garage", "", LocalDate.of(2026, 10, 8))
        val two = ask("get the oil changed", oil, listOf(jiffy, mechanic, plan), v + ("calendar:c2" to floatArrayOf(0.97f, 0.243f, 0f))).en()
        assertTrue(two, "Jiffy service" in two && "Also: “Car at the garage”" in two)
    }

    // review-last-time M3: the none line names only what was read; a calendar Robot may not read is said.
    @Test fun noneNamesOnlyTheSourcesRead() {
        assertEquals("I found nothing about that in your calendar or notes. Files isn't set up yet, so there were no receipts to look at.",
            LastTime.answer(emptyList(), today, LastRead(files = false)).en())
        val noCal = LastTime.answer(emptyList(), today, LastRead(calendar = false)).en()
        assertTrue(noCal, noCal.startsWith("I found nothing about that in your receipts or notes. I couldn't look in your calendar") && "Permissions" in noCal)
        assertTrue(LastTime.answer(emptyList(), today, LastRead(false, false)).en().startsWith("I found nothing about that in your notes."))
        val found = LastTime.respondRead(false, today) { LastFound(listOf(LastHit(jiffy, 1.0)), LastRead(calendar = false)) }.en()
        assertTrue(found, "Jiffy service" in found && "couldn't look in your calendar" in found && '\n' !in found)
        assertTrue(LastTime.answer(emptyList(), today, LastRead(calendar = false)).fr().contains("n’a pas l’autorisation"))
    }

    // review-last-time L1: French has a no-break space before ":" as said (the catalog types a plain one; FrTypo puts it, CatalogsTest).
    @Test fun frenchColonsHaveANoBreakSpace() {
        val log = LastCandidate(LastSource.LOGBOOK, "l1", "pilules", "", today)
        val say = listOf(ask("faire changer l'huile", oil, listOf(jiffy)), ask("vélo", null, listOf(note.copy(title = "Vélo"))),
            LastTime.answer(listOf(LastHit(log, 1.0)), today),
            LastTime.answer(listOf(LastHit(jiffy, 1.0), LastHit(jiffy.copy(id = "r3", day = jiffy.day.minusDays(1)), 1.0)), today),
            LastTime.respond(true, today) { emptyList() }, LastTime.answer(emptyList(), today, LastRead(calendar = false)))
        for (p in say) p.fr().let { assertTrue(it, " :" !in it && "\u00A0:" in it) }
    }

    // review-last-time L2: a statement is not a question, in English and French; "what was the last time" still is.
    @Test fun statementsAreNotQuestions() {
        fun p(q: String) = LastTimeRouter.parse(q)
        assertNull(p("the last time I checked it was fine"))
        assertNull(p("The last time I went there it rained"))
        assertEquals("got a haircut", p("What was the last time I got a haircut?")?.what)
        assertNull(p("la dernière fois que j'ai vérifié, tout allait bien"))
        assertEquals("allé chez le dentiste", p("La dernière fois que je suis allé chez le dentiste, c'était quand ?")?.what)
        assertEquals("see Dr Patel", p("When did I last see Dr Patel?")?.said)
    }
}
