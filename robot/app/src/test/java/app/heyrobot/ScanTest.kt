// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scans' layout-insensitive matching (Scan, Flat): what a reformat may change, and what it may not. */
class ScanTest {
    private val src = """
        object Thing {
            fun prepare(ctx: Context, action: Action,
                        untrusted: Boolean = run { false }): Prepared {
                check.lock?.let { return lockRefusal(it) }
                val s = "a } brace and ${'$'}{ map { it } } in a string"
                return TermuxPolicy.command(
                    analysis, source,
                    s,
                )
            }

            fun prepareAll() { }
            private fun get(url: String) = url
        }
    """.trimIndent()

    @Test fun reindentingRewrappingAndTrailingCommasDontMatter() {
        val f = Flat(src)
        assertTrue(f.has("return TermuxPolicy.command(analysis, source, s)"))
        assertTrue(f.has("fun prepare(ctx: Context, action: Action, untrusted: Boolean"))
        val re = Flat(src.replace("\n", "\n  ").replace("analysis, source,\n", "analysis,\n source, "))
        assertEquals(f.text, re.text)
    }

    @Test fun aNeedleNeverMatchesInsideALongerName() {
        val f = Flat(src)
        assertTrue(f.has("prepare("))
        assertFalse(f.has("repare("))
        assertFalse(f.has("fun prepareAl"))
        assertTrue(f.has("fun prepareAll"))
        assertEquals(1, f.count("fun get("))
        assertFalse(Flat("val x = 1").has("valx"))
        assertFalse(Flat("valx = 1").has("val x"))
    }

    @Test fun aBodyIsItsOwnBracesStringsAndDefaultsAside() {
        val body = Flat(src).body("fun prepare(")
        assertTrue(body.text, body.text.startsWith("{check.lock") && body.text.endsWith("s)}"))
        assertTrue(body.has("TermuxPolicy.command(") && !body.has("prepareAll"))
        // A signature that already holds the opening brace.
        assertEquals("{}", Flat(src).body("fun prepareAll() {").text)
        assertEquals("{}", Flat(src).body("fun prepareAll(").text)
        // An expression body, through its catch.
        val tried = Flat("private fun f(x: Int): Boolean = try {\n    g(x)\n} catch (_: Throwable) {\n    false\n}\nfun next() { }").body("private fun f(")
        assertEquals("{g(x)}catch(_:Throwable){false}", tried.text)
        // A call's trailing block, inside the parentheses it opened.
        assertEquals("{putString(\"a\",\"b\")}", Flat("showAssist(Bundle().apply {\n putString(\"a\", \"b\")\n})").body("showAssist(Bundle().apply {").text)
    }

    @Test fun aSignatureMissingOrTwiceFailsLoudly() {
        for (sig in listOf("fun nothing(", "Thing")) {
            val failed = runCatching { Flat(src + "\nobject Thing { }").body(sig) }.isFailure
            assertTrue(sig, failed)
        }
    }

    @Test fun orderAndPresenceAreBothRequired() {
        val f = Flat(src)
        assertInOrder(f, "check.lock?.let {", "TermuxPolicy.command(")
        assertTrue(runCatching { assertInOrder(f, "TermuxPolicy.command(", "check.lock?.let {") }.isFailure)
        assertTrue(runCatching { assertInOrder(f, "not there", "TermuxPolicy.command(") }.isFailure)
    }

    @Test fun aCallsArgumentsSpanItsLines() {
        val f = Flat("RLog.i(\"run\",\n    \"command\" to c)\nRLog.w(\"x\", f(\"(\"))")
        assertEquals(listOf("\"run\",\"command\"to c", "\"x\",f(\"(\")"), f.calls(Regex("""RLog\.[iw]\(""")))
    }
}
