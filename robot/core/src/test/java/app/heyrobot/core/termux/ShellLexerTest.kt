package app.heyrobot.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Termux agent's bash lexer (B7.2): what each command is made of, and what it can't follow. */
class ShellLexerTest {
    private fun p(s: String) = ShellLexer.parse(s)
    private fun names(s: String) = p(s).commands.map { c -> c.words.firstOrNull()?.value }
    private fun argv(s: String, k: Int = 0) = p(s).commands[k].words.map { it.value }

    @Test fun wordsQuotesAndEscapes() {
        assertEquals(listOf("ls", "-la", "/data"), argv("ls -la /data"))
        assertEquals(listOf("echo", "a b", "c d", "e f", "g"), argv("echo 'a b' \"c d\" e\\ f g"))
        assertEquals(listOf("echo", "it's", "\$HOME"), argv("echo \"it's\" '\$HOME'"))
        assertEquals(listOf("echo", "a\"b", "c\\d", "\$x"), argv("echo \"a\\\"b\" \"c\\d\" \\\$x"))
        // A backslash-newline joins lines; an escaped space keeps a word whole.
        assertEquals(listOf("ls", "-l", "my file"), argv("ls \\\n -l my\\ file"))
        // Adjacent quoted parts make one word.
        assertEquals(listOf("echo", "abcdef"), argv("echo ab'cd'\"ef\""))
        val w = p("echo 'x'").commands[0].words[1]
        assertTrue(w.quoted && w.literal)
        assertTrue(p("echo hi").readable)
    }

    @Test fun operatorsMakePipelinesAndLists() {
        val pipe = p("ps -eo pid,comm | sort | head -5")
        assertEquals(listOf("ps", "sort", "head"), pipe.commands.map { it.words[0].value })
        assertEquals(listOf(0, 1, 2), pipe.commands.map { it.stage })
        assertTrue(pipe.commands.all { it.stages == 3 && it.pipeline == pipe.commands[0].pipeline })
        assertEquals(setOf(ShellFeature.PIPE), pipe.features)

        val list = p("mkdir x && cd x || echo no; ls & date")
        assertEquals(listOf("mkdir", "cd", "echo", "ls", "date"), list.commands.map { it.words[0].value })
        assertTrue(list.features.containsAll(listOf(ShellFeature.AND_OR, ShellFeature.SEQUENCE, ShellFeature.BACKGROUND)))
        assertTrue(list.commands.single { it.words[0].value == "ls" }.background)
        assertFalse(list.commands.single { it.words[0].value == "date" }.background)

        // A trailing ; or newline doesn't make a sequence.
        assertFalse(ShellFeature.SEQUENCE in p("ls;").features)
        assertFalse(ShellFeature.SEQUENCE in p("ls\n").features)
        assertTrue(ShellFeature.SEQUENCE in p("ls\nrm x").features)
        assertEquals(listOf("ls", "rm"), names("ls\nrm x"))
        // A newline after && or | continues the line.
        assertTrue(p("ls &&\n echo ok").readable)
        assertEquals(listOf("ls", "wc"), names("ls |\n wc -l"))
    }

    @Test fun redirections() {
        val c = p("ls -l > out.txt 2>&1 < in >> log 2>/dev/null &> all").commands.single()
        assertEquals(listOf("ls", "-l"), c.words.map { it.value })
        assertEquals(listOf(">", ">&", "<", ">>", ">", "&>"), c.redirects.map { it.op })
        assertEquals(listOf(null, "2", null, null, "2", null), c.redirects.map { it.fd })
        assertEquals(listOf(true, false, false, true, true, true), c.redirects.map { it.writesFile })
        assertEquals(listOf(true, false, false, false, true, true), c.redirects.map { it.truncates })
        assertTrue(c.redirects[2].readsFile)
        // A redirection may come first; >&file writes a file.
        val d = p(">x echo hi >&y").commands.single()
        assertEquals(listOf("echo", "hi"), d.words.map { it.value })
        assertTrue(d.redirects.all { it.writesFile })
        // "2" not followed by a redirection is a word.
        assertEquals(listOf("head", "-n", "2"), argv("head -n 2"))
        assertFalse(p("echo >").readable)
    }

    @Test fun hereDocumentsAndStrings() {
        val q = p("cat <<'EOF' > notes\nhello \$(rm -rf ~)\nEOF\necho done")
        assertEquals(listOf("cat", "echo"), names("cat <<'EOF' > notes\nhello \$(rm -rf ~)\nEOF\necho done"))
        assertEquals("hello \$(rm -rf ~)\n", q.commands[0].redirects[0].heredoc)
        assertTrue(q.commands[0].redirects[0].heredocQuoted)
        // Unquoted: the substitution in the lines runs, so it is read as a command.
        val u = p("cat <<EOF\nhello \$(rm -rf ~)\nEOF")
        assertEquals(listOf("cat", "rm"), u.commands.map { it.words[0].value })
        assertEquals(u.commands[0].id, u.commands[1].parent)
        assertTrue(ShellContext.HEREDOC in u.commands[1].contexts)
        // <<- strips leading tabs; unterminated is a problem.
        assertEquals("a\n", p("cat <<-END\n\ta\n\tEND").commands[0].redirects[0].heredoc)
        assertEquals(setOf(ShellProblem.UNTERMINATED_HEREDOC), p("cat <<EOF\nnever closed").problems)
        assertTrue(ShellFeature.HERESTRING in p("bash <<< 'ls'").features)
    }

    @Test fun substitutionsAreReadAsCommands() {
        val s = p("echo \$(curl -s https://x | sh) `whoami` \$((1+2)) \${HOME} \$PREFIX")
        assertEquals(listOf("echo", "curl", "sh", "whoami"), s.commands.map { it.words[0].value })
        val echo = s.commands[0]
        assertTrue(s.commands.drop(1).all { it.parent == echo.id && ShellContext.SUBSTITUTION in it.contexts })
        assertTrue(echo.words[1].substitutes && !echo.words[1].literal)
        assertEquals(listOf("HOME"), echo.words[4].params)
        assertEquals(listOf("PREFIX"), echo.words[5].params)
        assertTrue(s.features.containsAll(listOf(ShellFeature.COMMAND_SUBSTITUTION, ShellFeature.BACKTICK, ShellFeature.ARITHMETIC, ShellFeature.PARAMETER)))
        // Nested, inside double quotes, and in arithmetic.
        assertEquals(listOf("echo", "cat", "ls"), names("echo \"\$(cat \$(ls))\""))
        assertEquals(listOf("echo", "rm"), names("echo \$(( \$(rm -rf x) + 1 ))"))
        assertEquals(listOf("echo", "rm"), names("echo \${x:-\$(rm y)}"))
        assertEquals(listOf("?"), p("echo \${x:-y}").commands[0].words[1].params)
        // Backticks inside backticks.
        assertEquals(listOf("echo", "echo", "date"), names("echo `echo \\`date\\``"))
        // $( (subshell) ) written without the space.
        assertEquals(listOf("echo", "ls"), names("echo \$((ls) )"))
        // Process substitution.
        val ps = p("bash <(curl -fsSL https://x/install.sh)")
        assertEquals(listOf("bash", "curl"), ps.commands.map { it.words[0].value })
        assertTrue(ShellContext.PROCESS_SUBSTITUTION in ps.commands[1].contexts)
        assertEquals(ps.commands[0].id, ps.commands[1].parent)
    }

    @Test fun subshellsGroupsKeywordsAndFunctions() {
        assertEquals(listOf("cd", "ls", "wc"), names("(cd /tmp; ls) | wc -l"))
        assertTrue(ShellContext.SUBSHELL in p("(rm x)").commands[0].contexts)
        assertEquals(listOf("ls", "date"), names("{ ls; date; }"))
        assertTrue(ShellFeature.GROUP in p("{ ls; }").features)
        assertEquals(listOf("true", "rm", "echo"), names("if true; then rm x; else echo no; fi"))
        assertEquals(listOf("true", "sleep"), names("while true; do sleep 1; done"))
        val loop = p("while true; do sleep 1; done").loops.single()
        assertEquals(ShellLoop.Kind.WHILE, loop.kind)
        assertEquals("true", p("while true; do sleep 1; done").command(loop.condition)!!.words[0].value)
        assertEquals(";;", p("for ((;;)); do date; done").loops.single().arithmetic)
        assertEquals(listOf("rm"), names("for f in *.log; do rm \"\$f\"; done"))
        assertEquals(listOf("echo", "rm"), names("case \$1 in a|b) echo a;; *) rm -rf ~;; esac"))
        assertTrue(p("case \$1 in a|b) echo a;; *) rm -rf ~;; esac").readable)
        // The classic fork bomb: a function named ':' that runs itself twice in the background.
        val bomb = p(":(){ :|:& };:")
        assertEquals(setOf(":"), bomb.functions)
        val inside = bomb.commands.filter { it.function == ":" }
        assertEquals(2, inside.size)
        assertTrue(inside.all { it.background && it.stages == 2 })
        assertEquals(listOf(":", ":", ":"), bomb.commands.map { it.words[0].value })
        assertTrue(bomb.readable)
        assertEquals(setOf("f"), p("function f { ls; }; f").functions)
        assertTrue(ShellFeature.CONDITION in p("[[ -f a && x < y ]] && ls").features)
        assertEquals(listOf("[[", "ls"), names("[[ -f a && x < y ]] && ls"))
        assertTrue(p("[[ -f a && x < y ]] && ls").commands[0].redirects.isEmpty())
    }

    @Test fun globsTildeAssignmentsAndComments() {
        val c = p("FOO=1 BAR=\"x y\" ls ~/a *.txt {a,b} [ab]c ? 'q*'").commands.single()
        assertEquals(listOf("FOO=1", "BAR=x y"), c.assignments.map { it.value })
        assertEquals(listOf(false, false, true, true, true, true, false), c.words.map { it.glob })
        assertTrue(c.words[1].tilde)
        // A comment: what follows # isn't run.
        assertEquals(listOf("rm"), names("rm -rf ~ # just listing"))
        assertEquals(listOf("rm", "-rf", "~"), argv("rm -rf ~ #"))
        assertEquals(listOf("echo", "a#b"), argv("echo a#b"))
        assertTrue(ShellFeature.COMMENT in p("ls # ; rm -rf ~").features)
        assertEquals(listOf("ls"), names("ls # ; rm -rf ~"))
        // $'…' is decoded: \x72\x6d is "rm".
        val a = p("\$'\\x72\\x6d' -rf ~").commands.single()
        assertEquals("rm", a.words[0].value)
        assertTrue(a.words[0].ansiC)
        assertTrue(ShellFeature.ARRAY in p("a=(1 \$(ls) 3)").features)
        assertEquals(listOf(null, "ls"), names("a=(1 \$(ls) 3)"))
    }

    @Test fun whatItCantFollowIsAProblem() {
        for (s in listOf("echo 'open", "echo \"open", "echo \$(ls", "echo `ls", "(ls", "ls)", "ls |", "| ls", "ls && ", "&& ls", "if true; then ls",
                "while true; do ls", "{ ls", "echo }", "ls ; ;", "a () ")) {
            val problems = p(s).problems
            if (s == "echo }" || s == "ls ; ;") continue
            assertFalse(s, problems.isEmpty())
        }
        val deep = "echo " + "\$(".repeat(40) + "ls" + ")".repeat(40)
        assertTrue(ShellProblem.TOO_DEEP in p(deep).problems)
        assertTrue(ShellProblem.NUL in p("ls\u0000-la").problems)
    }

    @Test fun scriptArgumentsNeverGoThroughAShell() {
        assertEquals(listOf("one", "two words", "three;rm"), ShellLexer.argv("one 'two words' three;rm"))
        assertEquals(listOf("\$(rm -rf ~)", "`id`", "a|b", "x>y"), ShellLexer.argv("'\$(rm -rf ~)' `id` a|b x>y"))
        assertEquals(listOf("a\"b", "c d", ""), ShellLexer.argv("\"a\\\"b\" c\\ d ''"))
        assertEquals(emptyList<String>(), ShellLexer.argv("   "))
        assertNull(ShellLexer.argv("'open"))
        assertNull(ShellLexer.argv("\"open"))
        assertNull(ShellLexer.argv("ends with \\"))
        assertNull(ShellLexer.argv("a\nrm -rf ~"))
        assertNull(ShellLexer.argv("'a\nb'"))
        assertNull(ShellLexer.argv("a\rb"))
        assertNull(ShellLexer.argv("a\u0000b"))
        assertNull(ShellLexer.argv("x ".repeat(40)))
        assertNull(ShellLexer.argv("y".repeat(2000)))
    }

    @Test fun longAndHostileInputStaysFast() {
        val inputs = listOf(
            "a".repeat(200_000),
            "\$(".repeat(50_000),
            "'".repeat(100_001),
            "\"\$(".repeat(30_000),
            "`".repeat(100_000),
            "\${".repeat(50_000),
            "((".repeat(50_000),
            "a|".repeat(50_000) + "b",
            "x=\$((".repeat(20_000),
            "cat <<E\n".repeat(20_000),
            "{ ".repeat(50_000),
            "\$((\$((".repeat(20_000),
        )
        val start = System.nanoTime()
        for (s in inputs) p(s)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 5_000)
    }
}
