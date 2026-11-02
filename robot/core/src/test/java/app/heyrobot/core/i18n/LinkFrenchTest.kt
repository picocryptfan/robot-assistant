// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import app.heyrobot.core.link.LinkCardText
import app.heyrobot.core.link.requests.EvPropose
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.LinkRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * 1.5 L13: Robot to Robot's words in core (LinkStrings) are Québec French all through: every entry has its French, none is
 * left the same as its English unless robot/l10n/same-in-french.txt names the word, no English word of scan_rules.json's
 * `en_words` is left, and what a card asks or warns says as much as its English, so no warning goes soft in translation.
 * CatalogsTest checks the placeholders, the plural forms and the spaces.
 */
class LinkFrenchTest {
    private val robot = File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val same: Set<String> = File(robot, "l10n/same-in-french.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { bare(it) }.toSet()
    private val english: Set<String> = JSONObject(File(robot, "l10n/scan_rules.json").readText()).getJSONArray("en_words").let { a ->
        (0 until a.length()).map { a.getString(it) }
    }.toSet() - "on"
    private val fr = Langs.of(Lang.FR)
    private val sam: Phrase = Phrase.plain("Sam")
    private val zone = ZoneId.of("America/Halifax")

    private fun bare(t: String): String = t.replace(Regex("""\{[A-Za-z][A-Za-z0-9_]*\}"""), " ").lowercase()
        .replace(Regex("""[^\p{L}\p{N}' ]"""), " ").replace(Regex(" +"), " ").trim()

    private fun flat(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ')

    @Test fun everyEntryHasItsFrench() {
        assertTrue(LinkStrings.all.size > 150)
        for (m in LinkStrings.all) assertTrue(m.id, m.translated(Lang.FR))
    }

    @Test fun noFrenchLeftTheSameAsItsEnglish() {
        val problems = mutableListOf<String>()
        for (m in LinkStrings.all) {
            val en = m.en.texts
            for (f in m.fr!!.texts) if (f in en) bare(f).takeIf { it.isNotEmpty() && it !in same && it != "robot" }?.let { problems += "${m.id}: \"$f\"" }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test fun noEnglishWordInTheFrench() {
        val problems = mutableListOf<String>()
        for (m in LinkStrings.all) for (t in m.fr!!.texts + m.voiceFr?.texts.orEmpty()) {
            bare(t).split(' ', '\'', '’').filter { it in english }.takeIf { it.isNotEmpty() }?.let { problems += "${m.id}: \"$t\" has $it" }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    /** What the English promises or warns, word for word where it matters: only busy times, never a payment, compare first. */
    @Test fun whatACardAsksKeepsItsFullMeaning() {
        val pins = mapOf(
            LinkStrings.askFreeBusy to "Seuls les moments occupés lui seraient renvoyés",
            LinkStrings.toFreeBusy to "Seuls les moments occupés reviennent",
            LinkStrings.moved to "Compare ton numéro de sécurité avant de l’accepter",
            LinkStrings.proposeFreeBusy to "Jamais les titres, les lieux ni les personnes",
            LinkStrings.proposeSplits to "Il ne paie jamais rien",
            LinkStrings.proposeFiles to "jusqu’à 50 Mo",
            LinkStrings.proposeShared to "seulement à partir de ce que tu as partagé",
            LinkStrings.proposeEvents to "quand tu es libre",
            LinkStrings.stoppedAlone to "Il te demande d’abord",
            LinkStrings.fileOffered to "Il part quand cette personne accepte",
            LinkStrings.fileFailed to "rien n’a été conservé",
            LinkStrings.evCancelled to "il n’y est plus",
            LinkStrings.toQuestion to "jamais inventée",
        )
        for ((m, words) in pins) assertTrue("${m.id}: $words", flat(FrTypo.fix(m.fr!!.texts.single())).contains(words))
    }

    @Test fun aCardReadsInFrenchWithFrenchDays() {
        val at = 1_792_000_800L
        val fb = LinkCardText.summary(LinkRequest.of(FbAsk(at, at + 6 * 3_600), at, "en"), sam, zone).render(fr)
        assertTrue(fb, fb.startsWith("Le Robot de Sam demande tes moments occupés"))
        val ev = LinkCardText.summary(LinkRequest.of(EvPropose("e1", 1, EventFields("Souper", listOf(at, at + 86_400), 90, null, null)), at, "en"), sam, zone).render(fr)
        assertTrue(ev, ev.startsWith("Le Robot de Sam propose un plan"))
        assertTrue(ev, " ou " in ev)
        for (t in listOf(fb, ev)) for (w in listOf(" at ", " to ", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun", " AM", " PM")) assertFalse("$t: $w", t.contains(w))
    }

    @Test fun whatTheyClaimIsInFrenchQuotesOnScreenAndBareAloud() {
        assertEquals("« Sam »", flat(LinkStrings.says("name" to "Sam").render(fr)))
        assertEquals("Sam", LinkStrings.says("name" to "Sam").render(Langs(Lang.FR, Lang.FR, Lang.FR, Medium.VOICE)))
    }
}
