// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.l10n

import app.heyrobot.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.1's guard: no new user-visible literal in the code. Every word Robot shows or says
 * comes from a resource (values/strings_<area>.xml, for the app's screens) or core's catalog (core/i18n/<Area>Strings.kt).
 * A literal the scan ([TextScan], the rules in robot/l10n/scan_rules.json) calls visible, outside the catalogs, fails
 * unless its area's baseline lists it: app/src/test/resources/l10n/baseline-<area>.txt, one line per literal of 1.1's
 * start ("<file from robot/>\t<SHA-1 of its text>"). The baselines only shrink: a line whose literal is gone fails too,
 * with "delete this line", and an area done with its words deletes its file. Prompts, words Robot hears, logs, keys and
 * the diagnostics' lines aren't visible text; a line that only looks like it ends with `// l10n: not text (reason)`.
 *
 * Each run writes what it found to app/build/l10n/ (the baselines as they would be, and guard.tsv with each literal's
 * line and text), so a package's progress is a diff, and `python3 -I robot/tools/strings_inventory.py robot --area <area>`
 * lists an area's literals.
 */
class UserTextGuardTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val rules = TextScan.Rules(File(root, "l10n/scan_rules.json"))
    private val baselineDir = File(root, "app/src/test/resources/l10n")
    private val rows by lazy { TextScan.inventory(root, rules) }

    private fun baseline(f: File): List<Pair<String, String>> = f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
        val parts = line.split('\t')
        assertEquals("${f.name}: \"$line\" is not <file>\\t<sha1>", 2, parts.size)
        parts[0] to parts[1]
    }

    private fun <T> counts(xs: List<T>): Map<T, Int> = xs.groupingBy { it }.eachCount()

    @Test fun noNewUserVisibleLiteral() {
        val guarded = rows.filter { it.guard == "baseline" }
        writeReport(guarded)
        val problems = mutableListOf<String>()
        for (r in rows.filter { it.guard == "bad-marker" }) problems += "${r.file}:${r.line}: \"// l10n:\" needs the form \"// l10n: not text (reason)\""
        for (r in guarded.filter { it.area == null }) problems += "${r.file}: no area in l10n/scan_rules.json's \"areas\""
        val files = baselineDir.listFiles { f -> f.name.startsWith("baseline-") }.orEmpty().sortedBy { it.name }
        val have = files.associate { f -> f.name.removePrefix("baseline-").removeSuffix(".txt") to baseline(f) }
        for (f in files) {
            val area = f.name.removePrefix("baseline-").removeSuffix(".txt")
            if (area !in rules.areaNames) problems += "${f.name}: \"$area\" is not an area of l10n/scan_rules.json"
            if (have.getValue(area).isEmpty()) problems += "${f.name} is empty: delete it (the area's code has no literal left)"
        }
        for (area in (rules.areaNames + have.keys).distinct()) {
            val want = counts(guarded.filter { it.area == area }.map { it.file to it.sha1 })
            val got = counts(have[area].orEmpty())
            for ((k, n) in got) {
                val extra = n - (want[k] ?: 0)
                if (extra > 0) problems += "baseline-$area.txt: \"${k.first}\t${k.second}\" matches no literal any more: delete this line" + if (extra > 1) " ($extra of them)" else ""
            }
            for ((k, n) in want) {
                val missing = n - (got[k] ?: 0)
                if (missing <= 0) continue
                val r = guarded.filter { it.file == k.first && it.sha1 == k.second }
                for (x in r.takeLast(missing)) {
                    problems += "${x.file}:${x.line}: new user-visible text \"${x.text}\": put it in values/strings_$area.xml (a screen) or core's " +
                        "catalog (core/i18n), or end the line with \"// l10n: not text (reason)\" if it isn't words someone reads or hears"
                }
            }
        }
        assertTrue(problems.size.toString() + " problem(s); the scan as it stands is in app/build/l10n/\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * §8.1: a rendered sentence is never glued to a literal (`text.render(l) + "…"`): word order differs in French, so the
     * whole sentence is one catalog entry or resource.
     */
    @Test fun noRenderedSentenceGluedToALiteral() {
        val glued = Regex("""\.(render\([^()]*\)|en\(\)|str\(\))\s*\+\s*"|"\s*\+\s*[\w.]+\.(render\([^()]*\)|en\(\)|str\(\))""")
        val bad = TextScan.sources(root, rules).filter { (_, f) -> glued.containsMatchIn(Source.strip(f.readText(), keepStrings = false)) }.map { it.first }
        assertEquals(emptyList<String>(), bad)
    }

    /** The rules the scan and robot/tools/strings_inventory.py share: each area named once, every module read, vendored code left out. */
    @Test fun theRulesNameEachAreaOnce() {
        assertEquals(rules.areaNames.distinct(), rules.areaNames)
        assertTrue(rules.areaNames.all { Regex("[a-z]+").matches(it) })
        // Every module's main sources were read, and the vendored ones left out.
        val read = TextScan.sources(root, rules).map { it.first }
        assertTrue(read.size > 300)
        assertTrue(read.none { it.contains("/com/k2fsa/") || it.contains("/readability/") })
        assertTrue(rules.modules.all { m -> read.any { it.startsWith("$m/") } })
    }

    /**
     * 1.4 F13: every Robot Files source is in the "files" area, so a new word there is Files' to translate: its own folders
     * (core files and parse, the app's ui/files and parse), and the files beside other areas' code by name; the other
     * areas' files whose names hold "Files" (WebFiles, TermuxFiles, HelperFiles, SpeedFiles, BackupFiles) stay theirs.
     */
    @Test fun robotFilesSourcesAreInTheFilesArea() {
        val read = TextScan.sources(root, rules).map { it.first }
        val files = read.filter { f ->
            f.startsWith("core/src/main/java/app/heyrobot/core/files/") || f.startsWith("core/src/main/java/app/heyrobot/core/parse/") ||
                f.startsWith("app/src/main/java/app/heyrobot/ui/files/") || f.startsWith("app/src/main/java/app/heyrobot/parse/") ||
                Regex("""/(Files\w*|File(ParseLink|Pixels)|Filing\w*|PanelFile\w*|ScanProvider)\.kt$""").containsMatchIn(f) &&
                !f.startsWith("core/src/main/java/app/heyrobot/core/i18n/") && "/core/memory/" !in f && "/core/backup/" !in f &&
                f != "core/src/main/java/app/heyrobot/core/Files.kt" // writeAtomic, every process's: not Robot Files
        }
        assertTrue(files.size >= 55)
        for (f in listOf("app/src/main/java/app/heyrobot/assistant/FilingWork.kt", "app/src/main/java/app/heyrobot/assistant/PanelFileTools.kt",
            "app/src/main/java/app/heyrobot/sys/FilePixels.kt", "app/src/main/java/app/heyrobot/parse/FileParser.kt", "core/src/main/java/app/heyrobot/core/parse/FileSniff.kt"))
            assertTrue(f, f in files)
        assertEquals(emptyList<String>(), files.filter { rules.area(it) != "files" }.map { "$it: ${rules.area(it)}" })
        for (f in listOf("app/src/main/java/app/heyrobot/sys/WebFiles.kt", "app/src/main/java/app/heyrobot/sys/TermuxFiles.kt",
            "app/src/main/java/app/heyrobot/sys/HelperFiles.kt", "app/src/main/java/app/heyrobot/sys/SpeedFiles.kt", "app/src/main/java/app/heyrobot/sys/BackupFiles.kt"))
            assertTrue(f, rules.area(f) != "files")
    }

    private fun writeReport(guarded: List<TextScan.Row>) {
        val out = File(root, "app/build/l10n").apply { mkdirs() }
        out.listFiles { f -> f.name.startsWith("baseline-") }?.forEach { it.delete() }
        for ((area, rs) in guarded.groupBy { it.area ?: "none" }) {
            File(out, "baseline-$area.txt").writeText(rs.map { "${it.file}\t${it.sha1}" }.sorted().joinToString("\n", postfix = "\n"))
        }
        File(out, "guard.tsv").writeText(guarded.joinToString("\n", postfix = "\n") { r ->
            listOf(r.area ?: "", r.file, r.line, r.lang, r.sha1, r.text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")).joinToString("\t")
        })
    }
}
