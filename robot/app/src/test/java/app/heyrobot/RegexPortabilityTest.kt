// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.net.URLClassLoader
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android's java.util.regex is ICU's regex engine, stricter than the JVM's the unit tests run: a pattern the JVM takes can
 * throw on the phone. A Regex in an object's or a class's static fields then fails that class's first use with
 * ExceptionInInitializerError (1.1: QueryCheck's bare "}" broke every web search, on the phone only). This compiles nothing
 * on ICU; [IcuRegex] knows the differences that throw, checked once against ICU 78's own compiler on every static regex
 * Robot had (it flagged exactly the one ICU refused). The scan covers every class of Robot's modules this test can load.
 */
class RegexPortabilityTest {
    @Test fun theCheckerKnowsWhatIcuRefuses() {
        // Refused by ICU (U_REGEX_RULE_SYNTAX, U_REGEX_LOOK_BEHIND_LIMIT), taken by the JVM.
        for (p in listOf("}", "a}", "x\\{at}", "(?<=a+)b", "(?<!a*)c", "(?<=a{2,})c", "(?U)\\w", "[a-z&&[^e]]x}")) {
            assertTrue(p, IcuRegex.problems(p).isNotEmpty())
        }
        assertTrue(IcuRegex.problems("\\w", Pattern.UNICODE_CHARACTER_CLASS).isNotEmpty())
        // Taken by both.
        for (p in listOf("\\{at\\}", "\\[at]", "]", "[]a]", "[^]a]", "[a}]", "[{]", "x{2,}", "x{2}", "x{1,3}?", "\\p{L}+", "[\\p{L}\\p{N}_]",
            "\\x{1F600}", "\\N{SPACE}", "\\Q{}\\E", "(?<n>a)", "(?<=ab?)c", "(?<=a{1,3})c", "(?iu)a", "(?s:a)", "\\{([a-zA-Z][a-zA-Z0-9_]*)\\}", "a++")) {
            assertEquals(p, emptyList<String>(), IcuRegex.problems(p))
        }
        // QueryCheck's e-mail pattern as 1.1's test build had it.
        assertTrue(IcuRegex.problems("\\b[\\p{L}\\p{N}._%+-]+\\s*(?:\\(at\\)|\\[at]|\\{at}|\\s(?:at|arobase|chez)\\s)").isNotEmpty())
        // FileReading's one-line fence before 1.1.0: its "}" closed no quantifier, so FileReading couldn't start on the phone.
        assertTrue(IcuRegex.problems("^```[A-Za-z]{0,10}[ \\t]*(\\{.*})[ \\t]*```$").isNotEmpty())
        assertEquals(emptyList<String>(), IcuRegex.problems("^```[A-Za-z]{0,10}[ \\t]*(\\{.*\\})[ \\t]*```$"))
    }

    @Test fun everyStaticRegexCompilesOnAndroid() {
        val found = staticPatterns()
        // The scan really reached Robot's classes (about 420 static regexes in 1.1): it can't pass by finding none.
        assertTrue("only ${found.size} static regexes found", found.size > 200)
        val bad = found.mapNotNull { (where, p) -> IcuRegex.problems(p.pattern(), p.flags()).takeIf { it.isNotEmpty() }?.let { "$where: $it" } }
        assertEquals(emptyList<String>(), bad)
    }

    /**
     * Every Regex and Pattern held in a static field (or a list, set, map, pair or array there) of Robot's classes, by where
     * it is. The classes start in a loader of their own, so no other test sees them started (or failed) here.
     */
    private fun staticPatterns(): List<Pair<String, Pattern>> {
        val urls = System.getProperty("java.class.path").split(File.pathSeparatorChar).filter { it.isNotEmpty() }.map { File(it).toURI().toURL() }
        val out = LinkedHashMap<String, Pair<String, Pattern>>()
        fun add(where: String, v: Any?, depth: Int) {
            if (v == null || depth > 3) return
            when {
                v is Pattern -> out.putIfAbsent("${v.flags()}:${v.pattern()}", where to v)
                // Kotlin's Regex and Pair from the other loader: the same names, other classes.
                v.javaClass.name == "kotlin.text.Regex" -> add(where, v.javaClass.getMethod("toPattern").invoke(v), depth)
                v.javaClass.name == "kotlin.Pair" -> listOf("getFirst", "getSecond").forEach { add(where, v.javaClass.getMethod(it).invoke(v), depth + 1) }
                v is Collection<*> -> v.forEach { add(where, it, depth + 1) }
                v is Map<*, *> -> { v.keys.forEach { add(where, it, depth + 1) }; v.values.forEach { add(where, it, depth + 1) } }
                v is Array<*> -> v.forEach { add(where, it, depth + 1) }
            }
        }
        // Its parent is the JVM's platform loader (the system loader's parent): java.*, none of the test's own classes.
        URLClassLoader(urls.toTypedArray(), ClassLoader.getSystemClassLoader().parent).use { loader ->
            for (name in robotClasses()) {
                // A class whose start needs Android (the JVM's android.jar only has stubs) can't be read here.
                val c = try { Class.forName(name, true, loader) } catch (_: Throwable) { continue }
                val fields = try { c.declaredFields } catch (_: Throwable) { continue }
                for (f in fields) {
                    if (!Modifier.isStatic(f.modifiers)) continue
                    try { f.isAccessible = true; add("$name.${f.name}", f.get(null), 0) } catch (_: Throwable) {}
                }
            }
        }
        return out.values.toList()
    }

    /** The names of the classes under app/heyrobot on the test's classpath: this module's, core's, helpers' and the rest. */
    private fun robotClasses(): List<String> {
        val names = sortedSetOf<String>()
        for (url in javaClass.classLoader.getResources("app/heyrobot")) {
            when (url.protocol) {
                "file" -> {
                    val root = File(url.toURI()).parentFile.parentFile
                    File(url.toURI()).walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                        .forEach { names += it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
                }
                "jar" -> (url.openConnection() as JarURLConnection).jarFile.use { jar ->
                    jar.entries().asSequence().map { it.name }.filter { it.startsWith("app/heyrobot/") && it.endsWith(".class") }
                        .forEach { names += it.removeSuffix(".class").replace('/', '.') }
                }
            }
        }
        // Not the tests themselves: only what ships.
        return names.filterNot { it.endsWith("Test") || it.contains("Test$") }
    }
}

/**
 * Where ICU's regex syntax (Android) is stricter than the JVM's, in the ways that throw: a "}" outside a character class
 * that closes no {n,m}; a lookbehind without a longest length (*, +, {n,}); and the UNICODE_CHARACTER_CLASS flag, inline
 * (?U) too. Everything else both engines refuse alike, so the JVM's own compile (the unit tests) already catches it.
 */
object IcuRegex {
    private val QUANTIFIER = Regex("""\{\d+(,\d*)?\}""")

    fun problems(p: String, flags: Int = 0): List<String> {
        val out = ArrayList<String>()
        if (flags and Pattern.UNICODE_CHARACTER_CLASS != 0) out += "UNICODE_CHARACTER_CLASS"
        if (flags and Pattern.LITERAL != 0) return out
        val groups = ArrayDeque<Boolean>() // each open group: is it a lookbehind
        var inClass = 0
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '\\') {
                val n = p.getOrNull(i + 1)
                if (n == 'Q') {
                    val e = p.indexOf("\\E", i + 2)
                    i = if (e < 0) p.length else e + 2
                    continue
                }
                if (n != null && n in "pPxN" && p.getOrNull(i + 2) == '{') {
                    val e = p.indexOf('}', i + 3)
                    i = if (e < 0) p.length else e + 1
                    continue
                }
                i += 2
                continue
            }
            if (inClass > 0) {
                // "]" right after "[" or "[^" is a literal.
                val first = p[i - 1] == '[' || (p[i - 1] == '^' && p.getOrNull(i - 2) == '[')
                if (c == '[') inClass++ else if (c == ']' && !first) inClass--
                i++
                continue
            }
            val behind = groups.any { it }
            when (c) {
                '[' -> inClass = 1
                '(' -> {
                    val lookbehind = p.startsWith("(?<=", i) || p.startsWith("(?<!", i)
                    if (!lookbehind && p.startsWith("(?", i)) {
                        val inline = Regex("""\(\?([a-zA-Z-]*)[:)]""").matchAt(p, i)
                        if (inline != null && 'U' in inline.groupValues[1]) out += "(?U) at $i"
                    }
                    groups.addLast(lookbehind)
                }
                ')' -> groups.removeLastOrNull()
                '{' -> {
                    val q = QUANTIFIER.matchAt(p, i)
                    if (q == null) out += "bare { at $i"
                    else {
                        if (behind && q.groupValues[1] == ",") out += "lookbehind without a longest length at $i"
                        i += q.value.length
                        continue
                    }
                }
                '}' -> out += "bare } at $i"
                // A possessive or lazy mark after a quantifier is no quantifier of its own.
                '*', '+' -> if (behind && (i == 0 || p[i - 1] !in "*+?}")) out += "lookbehind without a longest length at $i"
            }
            i++
        }
        return out
    }
}
