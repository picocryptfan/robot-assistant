// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 1.5, Robot to Robot (job L3): :link's connection log keeps codes and small numbers only. Its recording API takes only
 * [LinkEvent] and Ints, so a name, an address, a key or a request can't be handed to it; the file holds numbers alone and
 * survives a restart; a damaged line is skipped.
 */
class LinkLogTest {
    @Test fun theRecordingApiTakesCodesAndNumbersOnly() {
        val adds = LinkLog::class.java.declaredMethods.filter { it.name == "add" }
        assertEquals(1, adds.size)
        assertEquals(listOf(LinkEvent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), adds[0].parameterTypes.toList())
        // Every public method of the log takes nothing but those (or nothing).
        for (m in LinkLog::class.java.methods.filter { it.declaringClass == LinkLog::class.java && !it.isSynthetic })
            for (p in m.parameterTypes) assertTrue(m.name, p == LinkEvent::class.java || p == Int::class.javaPrimitiveType)
        // Ids are unique and stable.
        assertEquals(LinkEvent.entries.size, LinkEvent.entries.map { it.id }.toSet().size)
        assertEquals(16, LinkEvent.LISTEN.id)
        assertEquals(LinkWay.entries.size, LinkWay.entries.map { it.code }.toSet().size)
    }

    @Test fun theFileHoldsNumbersAndSurvivesARestart() {
        val dir = Files.createTempDirectory(File(System.getProperty("java.io.tmpdir")).toPath(), "linklog").toFile()
        try {
            val f = File(dir, LinkLogCodec.FILE)
            var t = 1_000L
            val log = LinkLog(f) { t++ }
            log.add(LinkEvent.CONNECT, LinkWay.DIRECT.code, 1)
            log.add(LinkEvent.DROPPED, LinkWay.PUSH.code, LinkProblem.BAD_MAGIC.code)
            val text = f.readText()
            assertTrue(text.startsWith(LinkLogCodec.HEADER + "\n"))
            for (line in text.lines().drop(1).filter { it.isNotEmpty() }) assertTrue(line, line.matches(Regex("-?\\d+( -?\\d+){3}")))
            val again = LinkLog(f) { t++ }
            assertEquals(listOf(LinkEvent.CONNECT, LinkEvent.DROPPED), again.snapshot().map { it.event })
            assertEquals(LinkProblem.BAD_MAGIC.code, again.snapshot()[1].b)
            // A damaged line or an unknown event is skipped; another header is nothing.
            assertEquals(1, LinkLogCodec.decode(LinkLogCodec.HEADER + "\n1 2 3 4\nzq9marker 1 2 3\n5 999 1 1\n1 2 3\n").size)
            assertEquals(0, LinkLogCodec.decode("robot-voice-log 1\n1 2 3 4\n").size)
            // At most CAPACITY kept.
            repeat(LinkLog.CAPACITY + 5) { again.add(LinkEvent.REQUEST_IN, it, 0) }
            assertEquals(LinkLog.CAPACITY, again.snapshot().size)
            assertFalse(f.readText().contains("zq9marker"))
            assertFalse(again.snapshot().toString().contains("zq9marker"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun anOpenCodeIsRestoredAsItWasKept() {
        val token = ByteArray(16) { (it + 1).toByte() }
        val (c1, a1) = OpenCode.of(token, 2_000).attempt(ByteArray(16) { 9 }, 1_000)
        assertEquals(CodeAttempt.WRONG, a1)
        val back = OpenCode.restore(c1.token, c1.expires, c1.wrong, c1.state)
        assertEquals(1, back.wrong)
        assertEquals(CodeAttempt.ACCEPTED, back.attempt(token, 1_001).second)
        // Four more wrong tries close it, as they would have before the restart.
        var c = back
        repeat(3) { c = c.attempt(ByteArray(16) { 9 }, 1_002).first }
        assertEquals(CodeAttempt.CLOSED, c.attempt(ByteArray(16) { 9 }, 1_003).second)
        assertTrue(runCatching { OpenCode.restore(token, 2_000, OpenCode.WRONG_TRIES, OpenCode.State.OPEN) }.isFailure)
        assertTrue(runCatching { OpenCode.restore(ByteArray(16), 2_000, 0, OpenCode.State.OPEN) }.isFailure)
    }
}
