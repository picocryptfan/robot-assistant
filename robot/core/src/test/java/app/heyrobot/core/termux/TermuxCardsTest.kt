package app.heyrobot.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a Termux card shows before and after a run, History's saved line, and what Gemma gets back (B5.2, B7.4). */
class TermuxCardsTest {
    private val on = TermuxSettings(enabled = true)

    private fun card(cmd: String, untrusted: Boolean = false, why: String? = "Shows the space used", settings: TermuxSettings = on): TermuxCardInfo {
        val a = CommandAnalyzer.analyze(cmd)
        return TermuxCards.command(a, TermuxPolicy.command(a, CommandSource.MODEL, settings, false, untrusted), why, settings.limitSeconds, written = true)
    }

    @Test fun aCommandsCardShowsRobotsOwnReadingAndGemmasReason() {
        val c = card("git -C ~/notes pull --ff-only", why = "  Gets the latest\nchanges  ")
        assertEquals("git -C ~/notes pull --ff-only", c.command)
        assertEquals(listOf("Changes, overwrites or moves files", "Uses the internet"), c.chips())
        assertNull(c.redLine())
        assertEquals("Gets the latest changes", c.why)
        assertTrue(c.tapOnly)
        assertFalse(c.fromContent)
        assertEquals("In: ~ · Time limit: 60 s", c.whereLine())
        // Deleting: a chip, and only a tap runs it.
        val del = card("find ~/logs -name '*.log' -mtime +30 -delete")
        assertTrue(del.chips().contains("Deletes files or empties them"))
        assertTrue(del.tapOnly)
        // A plain read: no chip, a yes may answer it.
        val du = card("du -sh ~")
        assertEquals(emptyList<String>(), du.chips())
        assertFalse(du.tapOnly)
        assertFalse(du.noCard)
        assertTrue(card("du -sh ~", settings = on.copy(readOnlyWithoutCard = true)).noCard)
        // Sending right away: the red line, not a chip.
        val sms = card("termux-sms-send -n 5551234 hello")
        assertEquals("Sends a text or places a call right away. Robot's own texting only opens a draft.", sms.redLine())
        assertFalse(sms.chips().any { it.startsWith("Sends") })
        // Rule 3: the card says where it came from.
        assertTrue(card("du -sh ~", untrusted = true).fromContent)
        // Needs a terminal: Open in Termux.
        assertTrue(card("htop").terminal)
        // Hidden characters are written out.
        assertEquals("ls ⟨U+202E⟩x", card("ls ‮x").command)
    }

    @Test fun quotesAndSpacesShowHowRobotSplitsTheWords() {
        assertEquals("ls with 2 arguments: “-la”, “My Files”", card("ls -la 'My Files'").readsAs)
        assertNull(card("ls -la").readsAs)
        assertNull(card("ls 'a' | head").readsAs)
    }

    @Test fun aScriptsCardShowsItsArgumentsAsArgv() {
        val d = TermuxPolicy.script("show-args", "one 'two words' three;rm", CommandSource.TYPED, on, false, false, false)
        val c = TermuxCards.script("show-args", ShellLexer.argv("one 'two words' three;rm")!!, d, 30)
        assertEquals("~/robot/show-args one 'two words' 'three;rm'", c.command)
        assertEquals("3 arguments: “one”, “two words”, “three;rm”", c.readsAs)
        assertEquals("show-args", c.script)
        assertEquals("In: ~/robot · Time limit: 30 s", c.whereLine())
        assertFalse(c.written)
        val auto = TermuxPolicy.script("backup", "", CommandSource.SPOKEN, on.copy(scripts = mapOf("backup" to ScriptLevel.AUTO)), false, false, true)
        assertTrue(TermuxCards.script("backup", emptyList(), auto, 60).noCard)
    }

    @Test fun historyKeepsTheSavedLineOnly() {
        assertEquals("Ran a command in Termux (exit 0)", TermuxCards.savedLine(null, 0))
        assertEquals("Ran your script “backup” (exit 2)", TermuxCards.savedLine("backup", 2))
        assertEquals("Ran a command in Termux (no answer)", TermuxCards.savedLine(null, null))
        assertEquals("Ran your script “backup” (stopped)", TermuxCards.savedLine("backup", null, stopped = true))
    }

    @Test fun theOutcomeInWords() {
        val r = TermuxOutput.prepare(TermuxResult("hello\n", "", 0))
        val ok = TermuxOutcome(0, 800, r)
        assertEquals("Exit 0 · 0.8 s", TermuxCards.exitLine(ok))
        assertEquals("Done in 0.8 s.", TermuxCards.said(ok))
        assertEquals("Exit 124 · 60 s: took longer than 60 s and was stopped", TermuxCards.exitLine(TermuxOutcome(124, 60_000, r)))
        assertEquals("Exit 143 · 2.0 s: stopped", TermuxCards.exitLine(TermuxOutcome(143, 2_000, r)))
        assertEquals(TermuxFailure.EXTERNAL_APPS_OFF.en, TermuxCards.exitLine(TermuxOutcome(null, 0, failure = TermuxFailure.EXTERNAL_APPS_OFF)))
        assertEquals(TermuxFailure.NO_ANSWER.en, TermuxCards.said(TermuxOutcome(null, 25_000, failure = TermuxFailure.NO_ANSWER)))
        assertEquals("812 characters", TermuxCards.size(812))
        assertEquals("4.1 KB", TermuxCards.size(4200))
        assertEquals("60 s", limitLabel(60))
        assertEquals("2 min", limitLabel(120))
        assertEquals("10 min", limitLabel(600))
    }

    @Test fun gemmaGetsTheExitCodeAndAtMost40Lines() {
        val out = (1..500).joinToString("\n") { "line $it" }
        val o = TermuxOutcome(0, 1200, TermuxOutput.prepare(TermuxResult(out, null, 0)))
        val m = TermuxCards.forModel(o).single()
        assertEquals(0, m["exit_code"])
        val text = m["output"] as String
        assertTrue(text.lines().size <= 41)
        assertTrue(text.length <= 1600)
        assertTrue(text.contains("lines not shown"))
        // Secrets are hidden before Gemma sees anything, and it is told so.
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQ\n-----END OPENSSH PRIVATE KEY-----"
        val s = TermuxCards.forModel(TermuxOutcome(0, 10, TermuxOutput.prepare(TermuxResult(key, null, 0)))).single()
        assertFalse((s["output"] as String).contains("b3BlbnNzaC1rZXkt"))
        assertEquals(1, s["secrets_hidden"])
    }
}
