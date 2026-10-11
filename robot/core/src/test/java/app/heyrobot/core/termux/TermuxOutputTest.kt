package app.heyrobot.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TermuxOutput (B7.4, B5.2): cleaning, caps, masking, the exit code in words, and Termux's failures. */
class TermuxOutputTest {
    @Test fun escapesAndControlCharactersGo() {
        assertEquals("red plain", TermuxOutput.clean("\u001b[1;31mred\u001b[0m plain"))
        assertEquals("text", TermuxOutput.clean("\u001b]0;window title\u0007text"))
        assertEquals("text", TermuxOutput.clean("\u001b]8;;https://x\u001b\\text\u001b]8;;\u001b\\"))
        assertEquals("ab", TermuxOutput.clean("a\u009b2Kb"))
        assertEquals("ab", TermuxOutput.clean("a\u001b(Bb"))
        assertEquals("a\nb\n", TermuxOutput.clean("a\r\nb\r\n"))
        assertEquals("100% done\n", TermuxOutput.clean("10%\r50%\r100% done\n"))
        assertEquals("a\tb", TermuxOutput.clean("a\u0000\u0007\b\u007f\tb"))
        assertEquals("ls; rm", TermuxOutput.clean("ls‮; rm​"))
        assertEquals("é ü 日本", TermuxOutput.clean("é ü 日本"))
        // A broken escape string can't swallow everything after it.
        assertTrue(TermuxOutput.clean("\u001b]" + "x".repeat(10_000) + "\nend").endsWith("end"))
        assertEquals("a�b", TermuxOutput.decode(byteArrayOf('a'.code.toByte(), 0xC3.toByte(), 'b'.code.toByte())))
        assertEquals("été", TermuxOutput.decode("été".toByteArray()))
    }

    @Test fun gemmaGetsTheFirstAndLastLines() {
        val text = (1..352).joinToString("\n") { "line $it" } + "\n"
        val m = TermuxOutput.forModel(text)
        val lines = m.lines()
        assertEquals(41, lines.size)
        assertEquals("line 1", lines.first())
        assertEquals("line 15", lines[14])
        assertEquals("… 312 lines not shown …", lines[15])
        assertEquals("line 328", lines[16])
        assertEquals("line 352", lines.last())
        assertTrue(TermuxOutput.forModel(text, fr = true).contains("… 312 lignes non affichées …"))
        assertEquals("a\nb", TermuxOutput.forModel("a\nb\n"))
        // And at most 1,500 characters.
        val wide = TermuxOutput.forModel("x".repeat(10_000))
        assertTrue(wide.length < 1_600)
        assertTrue(wide.contains("characters not shown"))
        assertTrue(TermuxOutput.forModel("y".repeat(5_000), maxChars = 800).length < 900)
    }

    @Test fun theCardKeepsTheFirstAndLast8KB() {
        assertEquals("short", TermuxOutput.forCard("short"))
        val big = "A".repeat(10_000) + "B".repeat(10_000)
        val card = TermuxOutput.forCard(big)
        assertTrue(card.startsWith("A".repeat(8 * 1024)))
        assertTrue(card.endsWith("B".repeat(8 * 1024)))
        assertTrue(card.contains("… 3.5 KB not shown …"))
        assertTrue(TermuxOutput.forCard(big, fr = true).contains("… 3,5 KB non affichés …"))
    }

    @Test fun preparedForCardAndModel() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk\n-----END OPENSSH PRIVATE KEY-----"
        val r = TermuxResult("\u001b[32mok\u001b[0m\n$key\n", "warning: token=ghp_" + "a".repeat(36) + "\n", 0)
        val o = TermuxOutput.prepare(r)
        assertEquals("ok\n[hidden: looks like a private key]\n[errors]\nwarning: token=[hidden: looks like a token]\n", o.card)
        assertEquals(2, o.masked)
        assertFalse(o.truncated)
        assertEquals("ok\n[hidden: looks like a private key]\n[errors]\nwarning: token=[hidden: looks like a token]", o.model)
        // "Hide secrets in output" off: shown as is (still cleaned).
        assertTrue(TermuxOutput.prepare(r, mask = false).card.contains("b3BlbnNzaC1rZXk"))
        // Robot's own keys by value; French labels.
        assertEquals("[masqué : une des clés de Robot]", TermuxOutput.prepare(TermuxResult("own-secret-value-123", null, 0), own = listOf("own-secret-value-123"), fr = true).card)
        // Only stderr.
        assertEquals("boom", TermuxOutput.prepare(TermuxResult("", "boom", 1)).card)
    }

    @Test fun cutsAreKnown() {
        // Termux cut it (its original length is longer than what came).
        assertTrue(TermuxOutput.prepare(TermuxResult("tail of it", null, 0, stdoutOriginalLength = "250000")).truncated)
        assertFalse(TermuxOutput.prepare(TermuxResult("all", null, 0, stdoutOriginalLength = "3")).truncated)
        assertFalse(TermuxOutput.prepare(TermuxResult("all", null, 0, stdoutOriginalLength = "garbage")).truncated)
        // Robot keeps at most 100 KB: the end.
        val o = TermuxOutput.prepare(TermuxResult("S" + "x".repeat(200_000) + "END", null, 0))
        assertTrue(o.truncated)
        assertEquals(100 * 1024, o.length)
        assertTrue(o.card.endsWith("END"))
        // A key whose start Termux cut off is hidden anyway.
        val cut = "ZXktdjEAAAAABG5vbmUAAAAEbm9uZQ\n-----END OPENSSH PRIVATE KEY-----\nafter"
        assertEquals("[hidden: looks like a private key]\nafter", TermuxOutput.prepare(TermuxResult(cut, null, 0)).card)
        val cert = "MIIB\n-----END CERTIFICATE-----\nafter"
        assertEquals(cert, TermuxOutput.prepare(TermuxResult(cert, null, 0)).card)
    }

    @Test fun exitCodesInWords() {
        assertEquals("Done in 0.8 s.", TermuxOutput.summary(0, 60, 812))
        assertEquals("Terminé en 0,8 s.", TermuxOutput.summary(0, 60, 812, fr = true))
        assertEquals("Took longer than 60 s and was stopped.", TermuxOutput.summary(124, 60, 61_000))
        assertEquals("A pris plus de 10 s et a été arrêtée.", TermuxOutput.summary(124, 10, 11_000, fr = true))
        assertEquals("Stopped.", TermuxOutput.summary(143, 60, 3_000))
        assertEquals("Arrêtée.", TermuxOutput.summary(137, 60, 3_000, fr = true))
        assertEquals("Command not found (exit 127).", TermuxOutput.summary(127, 60, 10))
        assertEquals("Couldn't run it: not executable or refused (exit 126).", TermuxOutput.summary(126, 60, 10))
        assertEquals("Ended by signal 2 (exit 130).", TermuxOutput.summary(130, 60, 10))
        assertEquals("Finished with exit code 2 in 12 s.", TermuxOutput.summary(2, 60, 12_400))
        assertEquals("Terminée avec le code 1 en 2 min 5 s.", TermuxOutput.summary(1, 600, 125_000, fr = true))
        assertEquals("Termux didn't answer.", TermuxOutput.summary(null, 60, 75_000))
        assertEquals("Exit 0 · 0.8 s", TermuxOutput.exitLine(0, 800))
        assertEquals("Code 1 · 1,5 s", TermuxOutput.exitLine(1, 1_500, fr = true))
    }

    @Test fun termuxFailuresNeverShowItsMessage() {
        assertNull(TermuxFailure.of(-1, "anything"))
        val secret = "bash -c 'curl -u me:hunter22 x'"
        assertEquals(TermuxFailure.EXTERNAL_APPS_OFF, TermuxFailure.of(2, "RUN_COMMAND failed: allow-external-apps is not true for $secret"))
        assertEquals(TermuxFailure.NOT_SET_UP, TermuxFailure.of(1, "Executable \"/data/data/com.termux/files/usr/bin/bash\": No such file or directory"))
        assertEquals(TermuxFailure.INTERNAL, TermuxFailure.of(150, secret))
        assertEquals(TermuxFailure.INTERNAL, TermuxFailure.of(3, null))
        for (f in TermuxFailure.entries) {
            assertFalse(f.en.contains("hunter22") || f.fr.contains("hunter22"))
            assertTrue(f.en.isNotBlank() && f.fr.isNotBlank() && f.en != f.fr)
        }
        assertEquals(TermuxFailure.entries.size, TermuxFailure.entries.map { it.id }.toSet().size)
    }
}
