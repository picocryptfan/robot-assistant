// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.l10n

import app.heyrobot.core.i18n.Catalogs
import app.heyrobot.core.i18n.FrTypo
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Robot's French uses Robot's words (robot/l10n/glossary.tsv): no variant the glossary
 * forbids in any French value (the app's values-fr-rCA and core's catalogs; brand names left out), tutoiement everywhere,
 * and « Réglages › » only before a Robot screen's title (core UiStrings), never Android's « Paramètres » pages.
 */
class GlossaryTest {
    /** Every French text: (where, text). */
    private val french: List<Pair<String, String>> by lazy {
        L10nFiles.frenchFiles().flatMap { f -> L10nFiles.entries("values-fr-rCA", f).flatMap { e -> e.values.values.map { "${e.file}: ${e.name}" to it } } } +
            Catalogs.all.flatMap { c -> c.all.flatMap { m -> listOfNotNull(m.fr, m.voiceFr).flatMap { it.texts }.map { m.id to it } } }
    }

    private fun word(w: String, ignoreCase: Boolean): Regex =
        Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}])",
            if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0).toRegex()

    @Test fun theGlossaryIsWellFormed() {
        val g = L10nFiles.glossary
        assertTrue(g.size >= 70)
        for (t in g) {
            assertTrue(t.english, t.check in setOf("word", "case", "path", "-"))
            assertTrue(t.english, t.french.isNotBlank())
            if (t.check == "word" || t.check == "case") assertTrue(t.english, t.never.isNotEmpty())
        }
    }

    @Test fun noForbiddenVariant() {
        val rules = L10nFiles.glossary.filter { it.check == "word" || it.check == "case" }.flatMap { t -> t.never.map { v -> t to word(v, t.check == "word") } }
        val problems = mutableListOf<String>()
        for ((where, text) in french) {
            val t = L10nFiles.withoutBrands(text.replace('’', '\''), keep = setOf("Robot"))
            for ((term, rx) in rules) rx.find(t)?.let { problems += "$where: « ${it.value} » (${term.english}: « ${term.french} »)" }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    /** The "le Robot" rule used to be unable to fail: the brand names, Robot among them, were cut out before it looked. */
    @Test fun theRobotRuleCanFail() {
        val rule = L10nFiles.glossary.first { it.english == "Robot" }
        assertTrue(rule.never.containsAll(listOf("le Robot", "du Robot", "au Robot")))
        val rx = word("le Robot", false)
        assertTrue(rx.containsMatchIn(L10nFiles.withoutBrands("Demande au téléphone, pas le Robot.", keep = setOf("Robot"))))
        assertTrue(!rx.containsMatchIn(L10nFiles.withoutBrands("Demande au téléphone, pas le Robot.")))
        assertTrue(!rx.containsMatchIn(L10nFiles.withoutBrands("Robot répond. Notes de Robot.", keep = setOf("Robot"))))
    }

    @Test fun reglagesNamesARobotScreen() {
        val titles = UiStrings.all.map { Phrase.M(it).render(app.heyrobot.core.i18n.Langs.of(Lang.FR)) }.sortedByDescending { it.length }
        val settings = FrTypo.fix(UiStrings.settings.fr!!.texts.single())
        val problems = mutableListOf<String>()
        for ((where, text) in french) {
            var at = text.indexOf("$settings › ")
            while (at >= 0) {
                val rest = text.substring(at + settings.length + 3).replace('\'', '’')
                if (titles.none { rest.startsWith(it) }) problems += "$where: « ${text.substring(at).take(40)} » names no Robot screen (core UiStrings)"
                at = text.indexOf("$settings › ", at + 1)
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }
}
