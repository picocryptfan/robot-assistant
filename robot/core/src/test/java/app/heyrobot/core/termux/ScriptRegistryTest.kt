package app.heyrobot.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Your scripts (B5.1 step 6, B5.3): the listing run read back, your words naming one (EN and FR), and the TERMUX group's words. */
class ScriptRegistryTest {
    private val scripts = listOf(
        TermuxScriptInfo("backup", "Copies my notes to the server", listOf("sauvegarde", "save notes")),
        TermuxScriptInfo("hello", "Says hello"),
        TermuxScriptInfo("show-args"),
    )

    private fun match(s: String, typed: Boolean = false) = ScriptRegistry.match(s, scripts, typed)

    @Test fun yourWordsNameAScript() {
        for (s in listOf("run backup", "Run my backup script", "run the backup script please", "please run backup", "can you run my backup script",
            "hey robot run backup", "start backup", "execute backup now", "lance backup", "Lance mon script de sauvegarde", "lance la sauvegarde",
            "exécute backup", "run save notes", "démarre le script backup", "peux-tu lancer backup s'il te plaît")) {
            assertEquals(s, ScriptMatch("backup", ""), match(s))
        }
        // A name with a dash is its words, said or typed.
        assertEquals(ScriptMatch("show-args", ""), match("run show args"))
        assertEquals(ScriptMatch("show-args", ""), match("run show-args"))
        assertEquals(ScriptMatch("hello", ""), match("Lance hello"))
    }

    @Test fun anythingElseStaysWithGemma() {
        for (s in listOf("run to the store", "run", "I run backups every day", "backup my phone", "how do I run backup", "run backup and then hello",
            "lance la commande uptime", "what does backup do", "run backups", "running backup", "start the timer", "run hello world")) {
            assertNull(s, match(s))
        }
        assertNull(ScriptRegistry.match("run backup", emptyList()))
        assertNull(match(""))
        assertNull(match("run " + "x".repeat(700)))
    }

    @Test fun typedArgumentsAreKeptAsWrittenAndSpokenOnesGoToGemma() {
        assertEquals(ScriptMatch("show-args", "one 'two words' three;rm"), match("run show-args one 'two words' three;rm", typed = true))
        assertEquals(ScriptMatch("backup", "--dry-run"), match("run my backup --dry-run", typed = true))
        assertEquals(ScriptMatch("backup", "--dry-run"), match("Run backup --dry-run", typed = true))
        // Spoken: too easy to mishear.
        assertNull(match("run show args one two", typed = false))
        // Arguments after an alias or the words of a name: only the exact name takes arguments.
        assertNull(match("lance sauvegarde maintenant demain", typed = true))
        // The arguments are split by ShellLexer.argv later, never by a shell.
        assertEquals(listOf("one", "two words", "three;rm"), ShellLexer.argv(match("run show-args one 'two words' three;rm", typed = true)!!.args))
    }

    @Test fun theListingIsReadBack() {
        val out = "robot-script\tbackup\tCopies my notes to the server\t sauvegarde, save notes\n" +
            "robot-script\thello\tSays hello\t\n" +
            "robot-script\t..evil\tnope\t\n" +
            "robot-script\tbad name\tspaces\t\n" +
            "robot-script\thello\tduplicate\t\n" +
            "robot-script\tcolors\t\u001b[31mRed\u001b[0m comment\t\n" +
            "robot-end\n"
        val l = ScriptRegistry.parse(out)
        assertFalse(l.folderMissing)
        assertTrue(l.complete)
        assertEquals(listOf("backup", "colors", "hello"), l.scripts.map { it.name })
        assertEquals(listOf("sauvegarde", "save notes"), l.scripts[0].aliases)
        assertEquals("Red comment", l.scripts[1].comment)
        assertEquals("backup — Copies my notes to the server", ScriptRegistry.line(l.scripts[0]))
        assertEquals("hello", ScriptRegistry.line(TermuxScriptInfo("hello")))
        assertTrue(ScriptRegistry.parse("robot-no-folder\n").folderMissing)
        assertFalse(ScriptRegistry.parse("robot-script\ta\t\t\n").complete)
        // At most 20, as Gemma is told at most 20 names.
        val many = (1..30).joinToString("") { "robot-script\ts$it\t\t\n" } + "robot-end\n"
        assertEquals(ScriptRegistry.MAX_SCRIPTS, ScriptRegistry.parse(many).scripts.size)
    }

    @Test fun theGroupsWordsCallTermux() {
        for (s in listOf("Run my backup script", "how much space is my Termux home using?", "pull the latest of my notes repo", "is my home server up?",
            "open htop", "what's using the CPU in Termux?", "show me the last 20 lines of the sync logs", "lance la commande uptime",
            "combien d'espace reste dans Termux ?", "Mon serveur répond-il ?", "use the command line", "ligne de commande")) {
            assertTrue(s, TermuxRouter.calls(s))
        }
        for (s in listOf("set a timer for 5 minutes", "what's the weather", "text Mom I'm late", "tell me a joke", "c'est l'heure du souper")) {
            assertFalse(s, TermuxRouter.calls(s))
        }
    }

    // --- The fixed listing run in a real bash ------------------------------------------------------------------------

    private val bash = listOf("/bin/bash", "/usr/bin/bash").map(::File).firstOrNull { it.canExecute() }

    @Test fun theListingRunsInBash() {
        assumeTrue(bash != null)
        val home = Files.createTempDirectory("robot-home").toFile()
        try {
            fun run(): String {
                val p = ProcessBuilder(bash!!.path, "--noprofile", "--norc", "-c", ScriptRegistry.LIST_SCRIPT).redirectErrorStream(false)
                    .also { it.environment()["HOME"] = home.path }.start()
                p.waitFor(20, TimeUnit.SECONDS)
                return p.inputStream.readBytes().decodeToString()
            }
            assertTrue(ScriptRegistry.parse(run()).folderMissing)
            val dir = File(home, "robot").apply { mkdirs() }
            File(dir, "backup").apply { writeText("#!/data/data/com.termux/files/usr/bin/sh\n# Copies my notes\n# aliases: sauvegarde, save notes\necho hi\n"); setExecutable(true) }
            File(dir, "hello").apply { writeText("#!/bin/sh\n# Says\thello\necho hello\n"); setExecutable(true) }
            File(dir, "notes.txt").writeText("# not executable\n")
            File(dir, "sub").mkdirs()
            val l = ScriptRegistry.parse(run())
            assertTrue(l.complete)
            assertEquals(listOf(TermuxScriptInfo("backup", "Copies my notes", listOf("sauvegarde", "save notes")), TermuxScriptInfo("hello", "Says hello")), l.scripts)
        } finally {
            home.deleteRecursively()
        }
    }
}
