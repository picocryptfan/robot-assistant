// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.l10n

import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What 1.1's resource tests read: the app's string resources as plain XML on the JVM
 * (as ProcessIsolationTest reads the manifest), and robot/l10n/'s lists: the areas done, the glossary, the values the same
 * in both languages, the rules (area names, resource prefixes, brands).
 */
internal object L10nFiles {
    val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    val res = File(root, "app/src/main/res")
    val rules = JSONObject(File(root, "l10n/scan_rules.json").readText())

    private fun lines(name: String) = File(root, "l10n/$name").readLines().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }

    val done: List<String> get() = lines("areas-done.txt").map { it.trim() }
    val sameInFrench: Set<String> get() = lines("same-in-french.txt").map { it.trim() }.toSet()
    val areaNames: List<String> = rules.getJSONArray("area_names").let { a -> (0 until a.length()).map { a.getString(it) } }
    val prefixes: Map<String, List<String>> = rules.getJSONObject("resource_prefixes").let { o ->
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }
    }
    val brands: List<String> = rules.getJSONArray("brands").let { a -> (0 until a.length()).map { a.getString(it) } }.sortedByDescending { it.length }

    /** One row of l10n/glossary.tsv. */
    data class Term(val english: String, val french: String, val never: List<String>, val check: String, val note: String)

    val glossary: List<Term> get() = lines("glossary.tsv").drop(1).map { line ->
        val c = line.split('\t')
        require(c.size == 5) { "glossary.tsv: \"$line\" has ${c.size} columns, not 5" }
        Term(c[0], c[1], c[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }, c[3], c[4])
    }

    /** A string resource: a <string>, the items of a <plurals> (by quantity) or of a <string-array> (by index). */
    data class Entry(val file: String, val name: String, val kind: String, val translatable: Boolean, val values: Map<String, String>, val raw: Map<String, String>)

    /** The string resources of res/[dir]/[file]. Values unescaped (\' \" \n \\), as Android shows them; raw as written. */
    fun entries(dir: String, file: String): List<Entry> {
        val f = File(res, "$dir/$file")
        if (!f.isFile) return emptyList()
        val rawText = f.readText()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val top = doc.documentElement.childNodes.let { nl -> (0 until nl.length).mapNotNull { nl.item(it) as? Element } }
        return top.filter { it.tagName in setOf("string", "plurals", "string-array") }.map { e ->
            val name = e.getAttribute("name")
            val values = when (e.tagName) {
                "string" -> mapOf("" to e.textContent)
                else -> e.getElementsByTagName("item").let { nl ->
                    (0 until nl.length).associate { i -> (nl.item(i) as Element).let { it.getAttribute("quantity").ifEmpty { i.toString() } to it.textContent } }
                }
            }
            Entry("$dir/$file", name, e.tagName, e.getAttribute("translatable") != "false", values.mapValues { unescape(it.value) }, rawOf(rawText, e.tagName, name, values.keys))
        }
    }

    /** The raw XML text of each value of an element (for the escaping checks). */
    private fun rawOf(text: String, tag: String, name: String, keys: Set<String>): Map<String, String> {
        val m = Regex("""<$tag\b[^>]*\bname="${Regex.escape(name)}"[^>]*>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL).find(text) ?: return emptyMap()
        if (tag == "string") return mapOf("" to m.groupValues[1])
        val items = Regex("""<item\b[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
        return keys.zip(items).toMap()
    }

    fun unescape(s: String): String {
        val t = s.trim().let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it }
        val out = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                out.append(when (val e = t[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> e })
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** The values/ string files: strings.xml and strings_<area>.xml. */
    fun englishFiles(): List<String> = File(res, "values").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().map { it.name }.sorted()

    fun frenchFiles(): List<String> = File(res, "values-fr-rCA").listFiles { f -> f.name.endsWith(".xml") }.orEmpty().map { it.name }.sorted()

    /** Android's format specifiers in a value ("%1\$s", "%d"), "%%" left out. */
    private val FORMAT = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?([a-zA-Z%])""")

    fun placeholders(s: String): List<String> = FORMAT.findAll(s).filter { it.groupValues[3] != "%" }.map { it.groupValues[1] + it.groupValues[3] }.sorted().toList()

    /** [s] with each Android placeholder as a core placeholder ("{p0}"), so core's FrTypo leaves it alone. */
    fun maskPlaceholders(s: String): String {
        var i = 0
        return FORMAT.replace(s) { m -> if (m.groupValues[3] == "%") m.value else "{p${i++}}" }
    }

    /**
     * [s] without the brand names (Proton Mail's "Mail" isn't the word mail), except those in [keep]: GlossaryTest keeps
     * "Robot", whose rule is about the words around it ("le Robot").
     */
    fun withoutBrands(s: String, keep: Set<String> = emptySet()): String = brands.filter { it !in keep }.fold(s) { acc, b -> acc.replace(b, " ") }
}
