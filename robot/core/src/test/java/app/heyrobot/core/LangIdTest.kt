// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Language ID for transcript lines (docs/conversation-mode.md 2.3): the table in assets/langid/fr-en.tsv on Tatoeba
 * CC0 lines it never saw (test resource langid/heldout-fr-en.tsv, written with it by tools/make_langid.py), the
 * Kotlin port against the script, not sure for digits and lone names, the colloquial set, and the label a line gets.
 */
class LangIdTest {
    private val asset = File(System.getProperty("user.dir") ?: ".", "src/main/assets/" + LangId.ASSET)
    private val id: LangId by lazy { LangId.load(asset.inputStream()) }

    private fun resource(name: String): List<List<String>> =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)
            .lines().filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t') }

    @Test fun theTableLoads() {
        assertEquals(listOf("fr", "en"), id.langs)
        assertFalse(id.provisional)
        assertTrue(id.size.toString(), id.size > 10_000)
        assertEquals(1.0, id.margin, 0.0)
        assertEquals(2.0, id.marginOneWord, 0.0)
        // Small enough to load in a moment on the phone.
        assertTrue(asset.length().toString(), asset.length() < 250_000)
    }

    @Test fun heldOutLinesMeetThePlansMarks() {
        val rows = resource("langid/heldout-fr-en.tsv")
        assertTrue(rows.size.toString(), rows.size >= 360)
        fun rate(range: IntRange): Double {
            val r = rows.filter { LangId.words(it[4]).size in range }
            assertTrue(range.toString(), r.size >= 60)
            return r.count { id.detect(it[4]).lang == it[1] }.toDouble() / r.size
        }
        // §9.4: at least 95 % on two-word lines and 99 % on four words or more; not sure counts as wrong.
        assertTrue(rate(2..2).toString(), rate(2..2) >= 0.95)
        assertTrue(rate(3..3).toString(), rate(3..3) >= 0.97)
        assertTrue(rate(4..99).toString(), rate(4..99) >= 0.99)
    }

    @Test fun theKotlinPortMatchesTheTrainingScript() {
        // Same features, same cue rules, same integer sums: the same answer and the same margin on every held-out line.
        for (r in resource("langid/heldout-fr-en.tsv")) {
            val g = id.detect(r[4])
            assertEquals(r[4], r[2], g.lang ?: "?")
            assertEquals(r[4], r[3].toInt() / 10.0, g.margin, 1e-9)
        }
    }

    @Test fun digitsAndLoneNamesAreNotSure() {
        for (t in listOf("5 h 30", "42", "14:30", "!!!", "", "  ", "3,50 \$", "A.")) {
            val g = id.detect(t)
            assertNull(t, g.lang)
            assertEquals(t, LangUnsure.NO_WORDS, g.why)
        }
        for (t in listOf("Montréal", "Gatineau", "Jean-François Tremblay", "Saguenay.")) {
            val g = id.detect(t)
            assertNull(t, g.lang)
            assertEquals(t, LangUnsure.NAME, g.why)
        }
        // A known word, or a name inside a sentence, is not a lone name.
        assertEquals("fr", id.detect("Merci.").lang)
        assertEquals("fr", id.detect("On se voit à Gatineau demain.").lang)
        assertEquals("en", id.detect("See you in Gatineau tomorrow.").lang)
        assertFalse(id.nameLike("Bonjour Marie !"))
    }

    @Test fun shortLinesOfEverydayTalk() {
        for (t in listOf("Oui.", "Merci.", "C'est correct.", "Pis toi?", "On se reprend-tu demain?", "Je te texte quand j'arrive.")) {
            assertEquals(t, "fr", id.detect(t).lang)
        }
        for (t in listOf("Yes.", "Thanks.", "No way!", "I'm on my way.", "What time is it?", "Can you repeat that?")) {
            assertEquals(t, "en", id.detect(t).lang)
        }
        val one = id.detect("Oui.")
        assertEquals(1, one.words)
        assertTrue(one.short && one.margin >= id.marginOneWord)
    }

    @Test fun theColloquialSet() {
        // Written for the research (30 Québec or casual French, 20 casual English): it may flatter the rules, so the mark
        // is that nothing is labelled wrong with confidence and at least 45 of 50 are right; the rest are not sure.
        val rows = resource("langid/colloquial.tsv")
        val labelled = rows.filter { it[0] != "mixed" }
        assertEquals(50, labelled.size)
        val wrong = labelled.filter { r -> id.detect(r[1]).lang.let { it != null && it != r[0] } }
        assertEquals(wrong.toString(), emptyList<List<String>>(), wrong)
        assertTrue(labelled.count { id.detect(it[1]).lang == it[0] } >= 45)
        // Code-switched lines take their main language (observed in the research, kept as a regression check).
        assertEquals("fr", id.detect("C'est full nice.").lang)
        assertEquals("en", id.detect("That's so quétaine.").lang)
        assertEquals("en", id.detect("It's a dépanneur on the corner.").lang)
        assertEquals("fr", id.detect("Je suis full busy cette semaine.").lang)
    }

    @Test fun featuresAreWordsAndPaddedGrams() {
        assertEquals(listOf("w:j'ai", "j", "'", "a", "i", " j", "j'", "'a", "ai", "i ", " j'", "j'a", "'ai", "ai ", " j'a", "j'ai", "'ai "),
            LangId.features("J’ai"))
        assertEquals(listOf("c'est", "full", "nice"), LangId.words("C'est FULL nice!"))
        assertEquals(listOf("aujourd'hui"), LangId.words("Aujourd’hui"))
    }

    @Test fun aShortLineWeighsWhatIsKnownAroundIt() {
        val oui = id.detect("Oui.")
        // The push-to-talk side always wins, and is not a guess.
        assertEquals(LineLang("en", false), LangLabel.choose(oui, forced = "en", speaker = "fr", yours = "fr"))
        // Agreeing with the speaker's language, or nothing known: its own language.
        assertEquals(LineLang("fr", false), LangLabel.choose(oui, speaker = "fr", yours = "en"))
        assertEquals(LineLang("fr", false), LangLabel.choose(oui, yours = "en"))
        // A weak short guess against the speaker's language: the speaker's, shown with "?".
        val weak = LangGuess("en", 3.0, 1, "en")
        assertEquals(LineLang("fr", true), LangLabel.choose(weak, speaker = "fr", yours = "en"))
        assertEquals(LineLang("fr", true), LangLabel.choose(weak, previous = "fr", yours = "en"))
        // ... unless it leads strongly.
        assertEquals(LineLang("en", false), LangLabel.choose(weak.copy(margin = 9.0), speaker = "fr", yours = "fr"))
        // A longer sure line keeps its language whatever came before.
        val long = id.detect("I think we should leave before the traffic gets worse.")
        assertEquals(LineLang("en", false), LangLabel.choose(long, speaker = "fr", previous = "fr", yours = "fr"))
        // Not sure: the speaker's majority, then the previous line, then the table's best guess, then yours.
        val name = id.detect("Montréal")
        assertEquals(LineLang("en", true), LangLabel.choose(name, speaker = "en", previous = "fr", yours = "fr"))
        assertEquals(LineLang("fr", true), LangLabel.choose(name, previous = "fr", yours = "en"))
        assertEquals(LineLang("fr", true), LangLabel.choose(name, yours = "en"))
        assertEquals(LineLang("fr", true), LangLabel.choose(LangGuess(null, 0.0, 0, null, LangUnsure.NO_WORDS), yours = "fr"))
        assertEquals("FR?", LangLabel.tag(LineLang("fr", true)))
        assertEquals("EN", LangLabel.tag(LineLang("en", false)))
    }

    @Test fun aSpeakersMajorityIgnoresGuesses() {
        assertEquals("fr", LangLabel.majority(listOf(LineLang("fr", false), LineLang("fr", false), LineLang("en", false), LineLang("en", true))))
        assertNull(LangLabel.majority(listOf(LineLang("fr", false), LineLang("en", false))))
        assertNull(LangLabel.majority(listOf(LineLang("fr", true))))
        assertEquals("fr", LangLabel.yours(java.util.Locale.CANADA_FRENCH))
        assertEquals("en", LangLabel.yours(java.util.Locale.CANADA))
    }

    @Test fun withoutTheTableAWeakerStandInAnswers() {
        val f = LangId.FALLBACK
        assertTrue(f.provisional)
        assertEquals("fr", f.detect("Je ne sais pas ce que tu veux dire.").lang)
        assertEquals("en", f.detect("I don't know what you mean.").lang)
        assertNull(f.detect("Tiguidou.").lang)
        // A damaged or missing table falls back instead of failing.
        assertSame(f, LangId.loadOrFallback { "not a table".byteInputStream() })
        assertSame(f, LangId.loadOrFallback { throw java.io.FileNotFoundException() })
        assertSame(f, LangId.loadOrFallback { "# robot-langid 1\n@langs\tfr\ten\nw:x\t1\n".byteInputStream() })
        val tiny = LangId.parse("# robot-langid 1\n@langs\tfr\ten\n@scale\t10\n@weights\t2\t1\n@margin\t1.0\t2.0\nw:oui\t-20\t-80\n")
        assertEquals("fr", tiny.detect("Oui").lang)
    }
}
