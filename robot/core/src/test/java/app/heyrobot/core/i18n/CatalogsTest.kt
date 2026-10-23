// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every entry of every catalog, rendered in English and French, on screen and aloud, with sample
 * arguments: no exception, no placeholder left, the same placeholders in both languages, plural forms that name their
 * count, French typography stable, no hand-typed no-break space, no fragment. An area listed in l10n/areas-done.txt has
 * French for every entry. Writes build/l10n/core.tsv (id, English, French, voice forms, placeholders) for the review
 * sheet.
 */
class CatalogsTest {
    private val robot = File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val areas: List<String> = JSONObject(File(robot, "l10n/scan_rules.json").readText()).getJSONArray("area_names").let { a ->
        (0 until a.length()).map { a.getString(it) }
    }
    private val done: List<String> = File(robot, "l10n/areas-done.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    private val entries = Catalogs.all.flatMap { c -> c.all.map { c to it } }

    private fun samples(m: Msg): List<Map<String, Arg>> {
        val names = m.placeholders(m.en) + m.placeholders(m.fr) + m.placeholders(m.voiceEn) + m.placeholders(m.voiceFr)
        val counts = if (m.count != null) listOf(0L, 1L, 2L, 1_000_000L) else listOf(null)
        return counts.map { n -> names.associateWith { if (it == m.count && n != null) Arg.Count(n) else Arg.Plain("Marie") } }
    }

    @Test fun everyCatalogIsRegisteredOnceWithItsOwnPrefixAndAnArea() {
        assertEquals(Catalogs.all.size, Catalogs.all.toSet().size)
        assertEquals(Catalogs.all.map { it.prefix }.distinct(), Catalogs.all.map { it.prefix })
        for (c in Catalogs.all) {
            assertTrue(c.prefix, Regex("[a-z]+").matches(c.prefix))
            assertTrue("${c.prefix}: ${c.area}", c.area in areas)
        }
        // Every area with words of its own in core has its catalog (Home and ADB fixes have resources only; 1.4's Files has
        // its own since F5, for what Robot says when a file comes in).
        assertEquals(areas.toSet() - setOf("home", "adb"), Catalogs.all.map { it.area }.toSet())
    }

    @Test fun idsAreUniqueAndPrefixed() {
        val ids = entries.map { it.second.id }
        assertEquals(ids.distinct(), ids)
        for ((c, m) in entries) {
            assertTrue(m.id, m.id.startsWith(c.prefix + ".") && Regex("[a-z0-9_]+").matches(m.id.removePrefix(c.prefix + ".")))
            assertEquals(m, Catalogs.msg(m.id))
        }
    }

    @Test fun theSamePlaceholdersInEveryForm() {
        for ((_, m) in entries) {
            val en = m.placeholders(m.en)
            for (f in listOfNotNull(m.fr, m.voiceEn, m.voiceFr)) assertEquals(m.id, en, m.placeholders(f))
            if (m.count != null) {
                assertTrue(m.id, m.en is Form.Counted && (m.fr == null || m.fr is Form.Counted))
                // Android's rule, French's too (its one is 0 and 1): every form names its count.
                for (t in m.texts) assertTrue("${m.id}: $t", "{${m.count}}" in t)
            } else assertTrue(m.id, m.en is Form.One)
        }
    }

    @Test fun everyEntryRendersInBothLanguagesOnScreenAndAloud() {
        for ((_, m) in entries) for (args in samples(m)) for (lang in Lang.entries) for (medium in Medium.entries) {
            val out = Phrase.M(m, args).render(Langs(lang, lang, lang, medium))
            assertFalse("${m.id} $lang $medium: $out", Msg.PLACEHOLDER.containsMatchIn(out))
            if (medium == Medium.VOICE) assertFalse("${m.id}: $out", Regex("[\u00A0\u202F«»“”›]").containsMatchIn(out))
            // (An entry not translated yet renders as an English sentence, English typography included.)
            if (lang == Lang.FR && medium == Medium.SCREEN && m.translated(Lang.FR)) assertEquals(m.id, FrTypo.fix(out), out)
        }
    }

    @Test fun noFragmentNoHandTypedSpace() {
        for ((_, m) in entries) {
            for (t in m.texts) assertEquals("${m.id}: \"$t\" starts or ends with a space", t.trim(), t)
            for (f in listOfNotNull(m.fr, m.voiceFr)) for (t in f.texts) {
                assertFalse("${m.id}: type ordinary spaces, render() puts U+00A0 and U+202F (FrTypo)", t.contains('\u00A0') || t.contains('\u202F'))
            }
        }
    }

    @Test fun aDoneAreaHasItsFrench() {
        assertEquals(done.distinct(), done)
        for (a in done) assertTrue("areas-done.txt: $a", a in areas)
        for ((c, m) in entries) if (c.area in done) assertTrue("${m.id}: area ${c.area} is done", m.fr != null && (m.voiceEn == null || m.voiceFr != null))
    }

    /** The catalogs as a table for the review sheet: build/l10n/core.tsv. */
    @Test fun export() {
        val out = File("build/l10n").apply { mkdirs() }
        fun cell(f: Form?): String = when (f) {
            null -> ""
            is Form.One -> f.text
            is Form.Counted -> listOfNotNull("one=" + f.forms.one, f.forms.many?.let { "many=$it" }, "other=" + f.forms.other).joinToString(" | ")
        }.replace("\t", " ").replace("\n", "\\n")
        val rows = entries.map { (c, m) -> listOf(c.area, m.id, cell(m.en), cell(m.fr), cell(m.voiceEn), cell(m.voiceFr), m.placeholders(m.en).sorted().joinToString(",")).joinToString("\t") }
        File(out, "core.tsv").writeText((listOf("area\tid\ten\tfr\tvoice_en\tvoice_fr\tplaceholders") + rows).joinToString("\n", postfix = "\n"))
        assertTrue(File(out, "core.tsv").length() > 0)
    }
}
