// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** The run plan (B8.2, B8.3): argv, extras, the fixed wrappers, and the wrappers run in a real bash with GNU timeout where present. */
class TermuxRunTest {
    @Test fun aCommandIsAnArgumentOfAFixedWrapper() {
        val command = "git -C ~/notes pull --ff-only; echo \"done\" \$(id)\nrm -rf ~ #"
        val p = TermuxRun.command(42, command, 60)
        assertEquals("/data/data/com.termux/files/usr/bin/bash", p.executable)
        assertEquals(listOf("--noprofile", "--norc", "-c", TermuxRun.wrapper(), "robot", "42", "60", command), p.arguments)
        assertFalse(TermuxRun.wrapper().contains("git"))
        assertEquals("/data/data/com.termux/files/home", p.workdir)
        assertEquals(TermuxRunKind.CARDED, p.kind)
        assertEquals((60 + 5 + 10) * 1000L, p.deadlineMs)
        assertEquals("/data/data/com.termux/files/usr/tmp/robot-42.pid", TermuxRun.pidFile(42))
        val extras = p.extras()
        assertEquals(listOf("com.termux.RUN_COMMAND_PATH", "com.termux.RUN_COMMAND_ARGUMENTS", "com.termux.RUN_COMMAND_WORKDIR", "com.termux.RUN_COMMAND_BACKGROUND",
            "com.termux.RUN_COMMAND_RUNNER", "com.termux.RUN_COMMAND_COMMAND_LABEL", "com.termux.RUN_COMMAND_PENDING_INTENT"), extras.keys.toList())
        assertEquals(TermuxExtra.Text(TermuxPaths.BASH), extras[TermuxIntents.EXTRA_COMMAND_PATH])
        assertEquals(TermuxExtra.Texts(p.arguments), extras[TermuxIntents.EXTRA_ARGUMENTS])
        assertEquals(TermuxExtra.Flag(true), extras[TermuxIntents.EXTRA_BACKGROUND])
        assertEquals(TermuxExtra.Text("app-shell"), extras[TermuxIntents.EXTRA_RUNNER])
        // Termux logs labels: always "Robot", never the command.
        assertEquals(TermuxExtra.Text("Robot"), extras[TermuxIntents.EXTRA_COMMAND_LABEL])
        assertEquals(TermuxExtra.ResultIntent(42), extras[TermuxIntents.EXTRA_PENDING_INTENT])
        // Limits are kept in range; the profile and stdin change only the fixed wrapper.
        assertEquals("10", TermuxRun.command(1, "ls", 1).arguments[6])
        assertEquals("600", TermuxRun.command(1, "ls", 99_999).arguments[6])
        assertTrue(TermuxRun.command(1, "ls", 60, profile = true).arguments[3].contains("bash -l -c \"\$3\""))
        val withInput = TermuxRun.command(1, "cat", 60, stdin = "hello")
        assertEquals(TermuxExtra.Text("hello"), withInput.extras()[TermuxIntents.EXTRA_STDIN])
        assertTrue(withInput.arguments[3].contains("<&3"))
        assertFalse(p.extras().containsKey(TermuxIntents.EXTRA_STDIN))
        for (bad in listOf<() -> Unit>({ TermuxRun.command(0, "ls", 60) }, { TermuxRun.command(-5, "ls", 60) }, { TermuxRun.command(1, " ", 60) },
                { TermuxRun.command(1, "ls\u0000x", 60) }, { TermuxRun.command(1, "ls", 60, workdir = "relative") })) {
            try { bad(); throw AssertionError("should refuse") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun wrappersAreFixedText() {
        assertEquals("p=\"\${TMPDIR:-/data/data/com.termux/files/usr/tmp}/robot-\$1.pid\"\n" +
            "GIT_TERMINAL_PROMPT=0 SSH_ASKPASS_REQUIRE=never timeout -k 5 \"\$2\" bash --noprofile --norc -c \"\$3\" </dev/null &\n" +
            "echo \$! > \"\$p\"; wait \$!; s=\$?; rm -f \"\$p\"; exit \$s", TermuxRun.wrapper())
        assertEquals("p=\"\${TMPDIR:-/data/data/com.termux/files/usr/tmp}/robot-\$1.pid\"\n" +
            "[ -f \"\$p\" ] || exit 0\n" +
            "g=\$(cat \"\$p\"); case \"\$g\" in ''|*[!0-9]*) exit 0;; esac\n" +
            "kill -TERM -- \"-\$g\" 2>/dev/null; sleep 2; kill -KILL -- \"-\$g\" 2>/dev/null; exit 0", TermuxRun.WRAP_STOP)
        assertTrue(TermuxRun.WRAP_SCRIPT.contains("timeout -k 5 \"\$2\" \"\$r\" \"\${@:5}\" </dev/null &"))
    }

    @Test fun scriptsStopTestAndTerminal() {
        val s = TermuxRun.script(7, "show-args", listOf("one", "two words", "three;rm", "\$(id)"), 30)
        assertEquals(listOf("--noprofile", "--norc", "-c", TermuxRun.WRAP_SCRIPT, "robot", "7", "30", "/data/data/com.termux/files/home/robot/show-args",
            "/data/data/com.termux/files/home/robot", "one", "two words", "three;rm", "\$(id)"), s.arguments)
        assertEquals(TermuxRunKind.SCRIPT, s.kind)
        for (bad in listOf("../x", "a/b", ".x", "")) {
            try { TermuxRun.script(7, bad, emptyList(), 30); throw AssertionError(bad) } catch (_: IllegalArgumentException) {}
        }
        val stop = TermuxRun.stop(8, 7)
        assertEquals(listOf("--noprofile", "--norc", "-c", TermuxRun.WRAP_STOP, "robot", "7"), stop.arguments)
        assertEquals(TermuxRunKind.STOP, stop.kind)
        assertEquals(listOf("--noprofile", "--norc", "-c", "echo robot-ok; command -v timeout", "robot"), TermuxRun.test(9).arguments)
        val t = TermuxRun.terminal(10, "htop")
        assertEquals(TermuxExtra.Text("terminal-session"), t.extras()[TermuxIntents.EXTRA_RUNNER])
        assertEquals(TermuxExtra.Flag(false), t.extras()[TermuxIntents.EXTRA_BACKGROUND])
        assertEquals(TermuxExtra.Text("2"), t.extras()[TermuxIntents.EXTRA_SESSION_ACTION])
    }

    // --- The wrappers in a real bash (GNU coreutils' timeout, as Termux's bootstrap has) ----------------------------------

    private val bash = listOf("/bin/bash", "/usr/bin/bash").map(::File).firstOrNull { it.canExecute() }
    private val tmp: File by lazy { Files.createTempDirectory("robot-termux").toFile() }

    private fun haveTools() {
        assumeTrue(bash != null)
        assumeTrue(System.getenv("PATH").orEmpty().split(':').any { File(it, "timeout").canExecute() && File(it, "realpath").canExecute() })
    }

    private fun start(args: List<String>): Process =
        ProcessBuilder(listOf(bash!!.path) + args).directory(tmp).redirectErrorStream(true).also { it.environment()["TMPDIR"] = tmp.path }.start()

    private fun run(args: List<String>, input: String? = null, closeInput: Boolean = true): Pair<Int, String> {
        val p = start(args)
        if (input != null) p.outputStream.write(input.toByteArray())
        if (closeInput) p.outputStream.close()
        assertTrue("didn't finish", p.waitFor(30, TimeUnit.SECONDS))
        return p.exitValue() to p.inputStream.readBytes().toString(Charsets.UTF_8)
    }

    private fun wrap(id: Int, limit: Int, command: String, stdin: Boolean = false) =
        listOf("--noprofile", "--norc", "-c", TermuxRun.wrapper(stdin = stdin), "robot", "$id", "$limit", command)

    /**
     * Every process whose command line holds [marker] has ended (Linux /proc): waited for, not slept on (a killed
     * process may take a moment to be reaped), up to a deadline far above any real wait.
     */
    private fun allGone(marker: String): Boolean {
        val until = System.nanoTime() + 20_000_000_000L
        while (running(marker) > 0) {
            if (System.nanoTime() > until) return false
            Thread.sleep(10)
        }
        return true
    }

    /** Processes whose command line holds [marker] (Linux /proc). */
    private fun running(marker: String): Int = File("/proc").listFiles().orEmpty().count { d ->
        d.name.all { it.isDigit() } && runCatching { File(d, "cmdline").readText().replace('\u0000', ' ').contains(marker) }.getOrDefault(false)
    }

    @Test fun exitCodesPassThroughAndThePidFileGoes() {
        haveTools()
        assertEquals(0 to "hi\n", run(wrap(11, 10, "echo hi")))
        assertEquals(3, run(wrap(12, 10, "exit 3")).first)
        assertEquals(127, run(wrap(13, 10, "no-such-command-zq9")).first)
        assertFalse(File(tmp, "robot-11.pid").exists())
        // The command is one argument: quotes and ; inside it are the inner bash's business only.
        assertEquals(0 to "a b;c\n", run(wrap(14, 10, "echo 'a b;c'")))
    }

    @Test fun theLimitEndsTheWholeProcessGroup() {
        haveTools()
        val start = System.nanoTime()
        val (code, _) = run(wrap(21, 1, "sleep 37.123 & sleep 37.123; wait"))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(124, code)
        assertTrue("took $ms ms", ms < 8_000)
        if (File("/proc/self").exists()) assertTrue(allGone("sleep 37.123"))
        assertFalse(File(tmp, "robot-21.pid").exists())
    }

    @Test fun stdinIsClosedUnlessGiven() {
        haveTools()
        // Our end of the pipe stays open: without </dev/null, cat would wait until the limit.
        val start = System.nanoTime()
        assertEquals(0 to "", run(wrap(31, 10, "cat"), closeInput = false))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        assertEquals(0 to "hello\n", run(wrap(32, 10, "cat", stdin = true), input = "hello\n"))
    }

    @Test fun stopEndsARunningCommand() {
        haveTools()
        val p = start(wrap(41, 30, "sleep 39.77 & sleep 39.77; wait"))
        val pid = File(tmp, "robot-41.pid")
        val until = System.currentTimeMillis() + 5_000
        while (!pid.exists() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(pid.exists())
        assertEquals(0, run(listOf("--noprofile", "--norc", "-c", TermuxRun.WRAP_STOP, "robot", "41")).first)
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertTrue("exit ${p.exitValue()}", p.exitValue() == 143 || p.exitValue() == 137)
        if (File("/proc/self").exists()) assertTrue(allGone("sleep 39.77"))
        // Stopping a run that already ended does nothing.
        assertEquals(0, run(listOf("--noprofile", "--norc", "-c", TermuxRun.WRAP_STOP, "robot", "41")).first)
        File(tmp, "robot-43.pid").writeText("1; touch pwned")
        assertEquals(0, run(listOf("--noprofile", "--norc", "-c", TermuxRun.WRAP_STOP, "robot", "43")).first)
        assertFalse(File(tmp, "pwned").exists())
    }

    @Test fun scriptsGetArgvAndMustBeInTheFolder() {
        haveTools()
        val dir = File(tmp, "robot").apply { mkdirs() }
        val other = File(tmp, "other").apply { mkdirs() }
        File(dir, "show-args").apply { writeText("#!/bin/sh\nfor a in \"\$@\"; do echo \"[\$a]\"; done\n"); setExecutable(true) }
        File(other, "evil").apply { writeText("#!/bin/sh\necho evil\n"); setExecutable(true) }
        File(dir, "plain").writeText("echo no\n")
        Files.createSymbolicLink(File(dir, "link").toPath(), File(other, "evil").toPath())
        fun script(path: String, vararg args: String) =
            run(listOf("--noprofile", "--norc", "-c", TermuxRun.WRAP_SCRIPT, "robot", "51", "10", path, dir.path) + args)
        val argv = ShellLexer.argv("one 'two words' three;rm \$(touch pwned)")!!
        assertEquals(0 to "[one]\n[two words]\n[three;rm]\n[\$(touch]\n[pwned)]\n", script(File(dir, "show-args").path, *argv.toTypedArray()))
        assertFalse(File(tmp, "pwned").exists())
        assertEquals(126, script(File(dir, "link").path).first)
        assertEquals(126, script("${dir.path}/../other/evil").first)
        assertEquals(126, script(File(dir, "plain").path).first)
        assertEquals(126, script(File(dir, "missing").path).first)
        assertFalse(File(tmp, "robot-51.pid").exists())
    }
}
