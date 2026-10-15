// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The source scans' lexer (Source): comments go, string literals are kept or emptied, and nothing inside a literal is
 * read as a comment. Before 1.0 rc1 the scans used regexes, and a "*" "/" "*" file type in a string opened a "comment" that
 * swallowed the rest of PeopleScreen.kt (and MainActivity.kt) up to the next comment's end, so their checks saw nothing there.
 */
class SourceTest {
    private val star = "*" + "/" + "*"

    @Test fun aFileTypeInAStringNoLongerHidesTheCodeAfterIt() {
        val src = "importer.launch(arrayOf(\"text/vcard\", \"$star\"))\nval hidden = Problems.share(ctx)\n/** a comment */\nfun after() = 1\n"
        val code = Source.strip(src, keepStrings = false)
        assertTrue(code, code.contains("val hidden = Problems.share(ctx)") && code.contains("fun after() = 1"))
        assertFalse(code, code.contains(star) || code.contains("a comment"))
        assertEquals("importer.launch(arrayOf(\"\", \"\"))\nval hidden = Problems.share(ctx)\n\nfun after() = 1\n", code)
        val kept = Source.strip(src, keepStrings = true)
        assertTrue(kept, kept.contains("\"$star\"") && kept.contains("val hidden") && !kept.contains("a comment"))
    }

    @Test fun literalsAndCommentsOfEveryKind() {
        val src = listOf(
            "val url = \"https://example.org/a\" // a comment with \"a quote",
            "val c = '\"'; val q = '\\''; val u = '\\u0041'",
            "val t = \"\${if (on) \"a /* b\" else \"c\"} done\" + Seen1()",
            "val r = \"\"\"raw // not a comment \${x(\"y\")} /* nor this \"\"\" + Seen2()",
            "/* outer /* inner */ still a comment */ Seen3()",
            "val e = \"escaped \\\" quote // and slashes\" + Seen4()",
            "fun `a name's quote`() = Seen5()",
        ).joinToString("\n")
        val code = Source.strip(src, keepStrings = false)
        for (n in 1..5) assertTrue("Seen$n: $code", code.contains("Seen$n()"))
        for (gone in listOf("https", "a comment", "a /* b", "raw", "inner", "still", "escaped")) assertFalse("$gone: $code", code.contains(gone))
        assertTrue(code, code.contains("val c = '\"'; val q = '\\''; val u = '\\u0041'"))
        val kept = Source.strip(src, keepStrings = true)
        for (k in listOf("\"https://example.org/a\"", "\"a /* b\"", "raw // not a comment", "escaped \\\" quote // and slashes")) assertTrue("$k: $kept", kept.contains(k))
        assertFalse(kept, kept.contains("a comment with") || kept.contains("inner"))
        // Java's block comments don't nest: the first end closes it.
        assertEquals(" still */ Seen3()", Source.strip("/* outer /* inner */ still */ Seen3()", keepStrings = true, kotlin = false))
    }

    /** The two files that hold that string are read whole now. */
    @Test fun peopleScreenAndMainActivityAreReadToTheEnd() {
        val root = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
        for ((path, after) in listOf("ui/PeopleScreen.kt" to "fun NotificationAccessScreen(", "ui/MainActivity.kt" to "Problems.copy(")) {
            val f = File(root, "app/src/main/java/app/heyrobot/$path")
            val text = f.readText()
            assertTrue(path, text.contains("\"$star\""))
            assertTrue(path, Source.strip(text, keepStrings = false).contains(after))
        }
    }
}
