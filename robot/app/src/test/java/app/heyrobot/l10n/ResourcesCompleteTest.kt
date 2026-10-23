// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1's resource files: one values/strings_<area>.xml per area, each holding only
 * its area's id prefixes, its French in values-fr-rCA/ under the same file name; strings.xml keeps only the app's name. For
 * an area in l10n/areas-done.txt, every English string has its French, with the same placeholders, the plural forms French
 * needs, and no French value left the same as its English (l10n/same-in-french.txt names the words that are). No value of
 * a done area starts or ends with a space: a sentence is one whole string, never glued from pieces.
 */
class ResourcesCompleteTest {
    private fun area(file: String): String? = Regex("strings_([a-z]+)\\.xml").matchEntire(file)?.groupValues?.get(1)

    @Test fun oneFilePerAreaHoldingOnlyItsOwnIds() {
        val problems = mutableListOf<String>()
        val english = L10nFiles.englishFiles()
        for (a in L10nFiles.areaNames) if ("strings_$a.xml" !in english) problems += "values/strings_$a.xml is missing"
        for (dir in listOf("values", "values-fr-rCA")) {
            val files = if (dir == "values") english else L10nFiles.frenchFiles()
            for (f in files) {
                if (f == "strings.xml" && dir == "values") continue
                val a = area(f)
                if (a == null || a !in L10nFiles.areaNames) { problems += "$dir/$f: not strings_<area>.xml of an area in l10n/scan_rules.json"; continue }
                val ok = L10nFiles.prefixes.getValue(a)
                for (e in L10nFiles.entries(dir, f)) if (ok.none { e.name.startsWith(it) }) problems += "$dir/$f: ${e.name} doesn't start with ${ok.joinToString(" or ")}"
            }
        }
        for (f in L10nFiles.frenchFiles()) if (f !in english) problems += "values-fr-rCA/$f has no English file"
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test fun stringsXmlKeepsOnlyTheAppsName() {
        val e = L10nFiles.entries("values", "strings.xml")
        assertEquals(listOf("app_name"), e.map { it.name })
        assertTrue("app_name is translatable=\"false\"", !e.single().translatable)
        assertEquals("Robot", e.single().values.getValue(""))
    }

    @Test fun eachIdOnceAcrossTheFiles() {
        val all = L10nFiles.englishFiles().flatMap { f -> L10nFiles.entries("values", f) }.map { it.name }
        assertEquals(all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toString(), all.size, all.toSet().size)
    }

    @Test fun apostrophesAreEscaped() {
        // aapt2 refuses a bare ' in English; French writes the typographic ’ (FrenchTypographyTest), never a bare one either.
        val bad = (L10nFiles.englishFiles().flatMap { L10nFiles.entries("values", it) } + L10nFiles.frenchFiles().flatMap { L10nFiles.entries("values-fr-rCA", it) })
            .flatMap { e -> e.raw.values.filter { r -> !r.trim().startsWith("\"") && Regex("""(?<!\\)'""").containsMatchIn(r) }.map { "${e.file}: ${e.name}" } }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun aDoneAreaHasItsFrench() {
        val done = L10nFiles.done
        assertEquals(done.distinct(), done)
        val problems = mutableListOf<String>()
        for (a in done) {
            if (a !in L10nFiles.areaNames) { problems += "areas-done.txt: \"$a\" is not an area"; continue }
            val f = "strings_$a.xml"
            val en = L10nFiles.entries("values", f).filter { it.translatable }.associateBy { it.name }
            val fr = L10nFiles.entries("values-fr-rCA", f).associateBy { it.name }
            for ((name, e) in en) {
                val x = fr[name]
                if (x == null) { problems += "values-fr-rCA/$f: $name is missing ($a is done)"; continue }
                if (x.kind != e.kind) { problems += "values-fr-rCA/$f: $name is a ${x.kind}, not a ${e.kind}"; continue }
                // The same placeholders: a string's and an array item's as its English; every plural form all of the English forms'.
                val enAll = e.values.values.flatMap { L10nFiles.placeholders(it) }.toSet()
                for ((k, v) in x.values) {
                    val want = if (e.kind == "plurals") enAll else L10nFiles.placeholders(e.values[k].orEmpty()).toSet()
                    val got = L10nFiles.placeholders(v).toSet()
                    if (got != want) problems += "values-fr-rCA/$f: $name[$k] has placeholders ${got.sorted()}, the English ${want.sorted()}"
                }
                if (e.kind == "plurals") {
                    if (!e.values.keys.containsAll(listOf("one", "other"))) problems += "values/$f: $name needs one and other"
                    if (!x.values.keys.containsAll(listOf("one", "many", "other"))) problems += "values-fr-rCA/$f: $name needs one, many and other"
                }
                if (e.kind == "string-array" && x.values.size != e.values.size) problems += "values-fr-rCA/$f: $name has ${x.values.size} items, the English ${e.values.size}"
                for ((k, v) in x.values) if (v == e.values[k] && v.trim() !in L10nFiles.sameInFrench) {
                    problems += "values-fr-rCA/$f: $name is the same as its English (\"$v\"); a word that is goes in l10n/same-in-french.txt"
                }
                for (v in e.values.values + x.values.values) if (v != v.trim()) problems += "$f: $name \"$v\" starts or ends with a space"
            }
            for (name in fr.keys - en.keys) problems += "values-fr-rCA/$f: $name has no English (or it is translatable=\"false\")"
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }
}
