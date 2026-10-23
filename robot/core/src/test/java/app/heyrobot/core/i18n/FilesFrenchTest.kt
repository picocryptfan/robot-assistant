// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import app.heyrobot.core.files.FileMoneyText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * 1.4 F13: Robot Files' words in core (FilesStrings, and BackupStrings' Files lines) are Québec French all through. Every
 * entry has its French; none is left the same as its English unless robot/l10n/same-in-french.txt names the word (as
 * ResourcesCompleteTest asks of the screens); no English word of scan_rules.json's `en_words` is left in a French text; and
 * what Robot says aloud has amounts and dates in words its French voice reads right (« 84 dollars et 12 », never « $ » or
 * « cents », which it would read as a hundred). CatalogsTest checks the placeholders, the plural forms and the spaces.
 */
class FilesFrenchTest {
    private val robot = File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val same: Set<String> = File(robot, "l10n/same-in-french.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { bare(it) }.toSet()
    /** scan_rules.json's English words, but « on », which is French too. */
    private val english: Set<String> = JSONObject(File(robot, "l10n/scan_rules.json").readText()).getJSONArray("en_words").let { a ->
        (0 until a.length()).map { a.getString(it) }
    }.toSet() - "on"
    private val entries: List<Msg> = FilesStrings.all + BackupStrings.all.filter { "files" in it.id }
    private val fr = Langs.of(Lang.FR)
    private val voice = Langs(Lang.FR, Lang.FR, Lang.FR, Medium.VOICE)

    /** [t] without its placeholders and punctuation, in lower case: what is left to compare. */
    private fun bare(t: String): String = t.replace(Regex("""\{[A-Za-z][A-Za-z0-9_]*\}|%(\d\$)?[ds]"""), " ").lowercase()
        .replace(Regex("""[^\p{L}\p{N}' ]"""), " ").replace(Regex(" +"), " ").trim()

    private fun pairs(m: Msg): List<Pair<String, String>> {
        val en = m.en
        val f = m.fr ?: return emptyList()
        return when {
            en is Form.One && f is Form.One -> listOf(en.text to f.text)
            en is Form.Counted && f is Form.Counted -> listOfNotNull(en.forms.one to f.forms.one, en.forms.other to f.forms.other)
            else -> emptyList()
        }
    }

    @Test fun everyFilesEntryHasItsFrench() {
        assertTrue(entries.size > 200)
        for (m in entries) assertTrue(m.id, m.translated(Lang.FR))
    }

    @Test fun noFrenchLeftTheSameAsItsEnglish() {
        val problems = mutableListOf<String>()
        for (m in entries) for ((en, f) in pairs(m)) {
            val b = bare(f)
            if (en == f && b.isNotEmpty() && b !in same && b != "robot") problems += "${m.id}: \"$f\" (a word that is the same goes in l10n/same-in-french.txt)"
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test fun noEnglishWordInTheFrench() {
        val problems = mutableListOf<String>()
        for (m in entries) for (t in m.fr?.texts.orEmpty() + m.voiceFr?.texts.orEmpty()) {
            val words = bare(t).split(' ', '\'').filter { it.isNotEmpty() }
            words.filter { it in english }.takeIf { it.isNotEmpty() }?.let { problems += "${m.id}: \"$t\" has $it" }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test fun amountsAloudInWords() {
        fun said(cents: Long, cur: String?) = FilesStrings.money("m" to Arg.Money(cents, cur)).render(voice)
        assertEquals("84 dollars et 12", said(8412, "CAD"))
        assertEquals("142 dollars", said(14200, "CAD"))
        assertEquals("1 dollar et 5", said(105, "CAD"))
        assertEquals("0 dollar et 99", said(99, "CAD"))
        assertEquals("moins 5 dollars", said(-500, "CAD"))
        assertEquals("1 234 567 dollars et 89", said(123456789, "CAD"))
        assertEquals("45 dollars américains et 50", said(4550, "USD"))
        assertEquals("1 dollar américain", said(100, "USD"))
        assertEquals("20 euros", said(2000, "EUR"))
        // FX2 (files-1.4-review.md R4), deliberately: pounds in words too (« 84,12 GBP » was read letter by letter).
        assertEquals("84 livres sterling et 12", said(8412, "GBP"))
        // Another currency, or none, as the screen writes it (no « $ » to read).
        assertEquals("84,12 JPY", said(8412, "JPY"))
        assertEquals("84,12", said(8412, null))
        // The screen keeps « 84,12 $ »; English, on screen and aloud, is unchanged.
        assertEquals("84,12 $", Fmt.money(8412, "CAD", Lang.FR))
        assertEquals("$84.12", Fmt.money(8412, "CAD", Lang.EN, Medium.VOICE))
    }

    @Test fun whatRobotSaysOfMoneyAndDatesReadsRight() {
        val sept = LocalDate.of(2026, 9, 1)
        val spent = FilesStrings.spent("amount" to FileMoneyText.amounts(mapOf("CAD" to 31240L)), "what" to Arg.Sub(FilesStrings.onFuel()),
            "when" to Arg.Sub(FileMoneyText.period(sept, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 22))!!), "count" to Arg.Sub(FilesStrings.countReceipts(9)))
        assertEquals("Tu as dépensé 312,40 $ en essence en septembre 2026, d’après 9 reçus.", spent.render(fr))
        assertEquals("Tu as dépensé 312 dollars et 40 en essence en septembre 2026, d’après 9 reçus.", spent.render(voice))
        // A date after a verb takes « le »; a merchant after « facture » takes « de » or « d' ».
        val due = FilesStrings.briefDueSaid("merchant" to Arg.Sub(FilesStrings.of("Bell")), "day" to Arg.Sub(FilesStrings.onDate("d" to Arg.Date(LocalDate.of(2026, 11, 1)))),
            "amount" to Arg.Money(8999, "CAD"))
        assertEquals("Ta facture de Bell est due le 1er novembre 2026: 89 dollars et 99.", due.render(voice))
        assertEquals("Your Bell bill is due November 1, 2026: $89.99.", due.render(Langs.of(Lang.EN)))
        assertEquals("d’Esso", FilesStrings.of("Esso").render(fr))
        assertEquals("d’Hydro-Québec", FilesStrings.of("Hydro-Québec").render(fr))
        assertEquals("d’Énergir", FilesStrings.of("Énergir").render(fr))
        assertEquals("de Costco", FilesStrings.of("Costco").render(fr))
        assertEquals("Costco", FilesStrings.of("Costco").render(Langs.of(Lang.EN)))
        // Nothing Robot says aloud carries the screen's marks.
        for (m in FilesStrings.all) for (t in listOfNotNull(m.voiceFr).flatMap { it.texts }) assertFalse(t, Regex("[«»›$]").containsMatchIn(t))
    }
}
