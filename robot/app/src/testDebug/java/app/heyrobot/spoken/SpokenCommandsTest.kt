// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpokenCommandsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Port : SpokenPort {
        var kinds: List<String>? = emptyList()
        var ended = 0
        var reset = 0
        val set = ArrayList<Pair<String?, String?>>()
        var opened: String? = null
        var boom = false
        override var recording = false
        override fun cards() = if (boom) throw IllegalStateException("x") else kinds
        override fun listening() = true
        override fun listenModel() = true
        override fun gemmaModel() = false
        override fun resetSettings() { reset++ }
        override fun setSetting(name: String?, value: String?) = (name == "wakeWord").also { if (it) set += name to value }
        override fun open(how: String): String? { opened = how; return null }
        override fun clip(name: String) = if (name.endsWith("1.s16")) ShortArray(4) { 7 } else null
        override fun end() { ended++ }
        override fun importModels() = 3
    }

    private val port = Port()
    private val file by lazy { File(tmp.root, "spoken/results.jsonl") }
    private val feed = SpokenFrameFeed(nanos = { 1L }, sleep = {}, allowed = { true })
    private val cmd by lazy { SpokenCommands(port, SpokenResults(file), feed) }
    private val say = "say-0000000000000001.s16"
    private val no = "no-0000000000000001.s16"

    private fun acks() = file.readLines().map { JSONObject(it) }
    private fun last(seq: Int, c: String?, a: String? = null, v: String? = null): JSONObject {
        val before = if (file.exists()) acks().size else 0
        cmd.handle(seq, c, a, v)
        val all = acks()
        assertEquals("exactly one ack for $c", before + 1, all.size)
        return all.last().also {
            assertEquals("ack", it.getString("type"))
            assertEquals(seq, it.getInt("seq"))
        }
    }

    @Test fun statusSaysTheProtocolTheModelsAndTheCards() {
        port.kinds = listOf("set_timer")
        val d = last(1, "status").getJSONObject("data")
        assertEquals(1, d.getInt("protocol"))
        assertTrue(d.getJSONObject("models").getBoolean("listen"))
        assertFalse(d.getJSONObject("models").getBoolean("gemma"))
        assertTrue(d.getBoolean("listening"))
        assertEquals("set_timer", d.getJSONArray("cards").getString(0))
    }

    @Test fun anUnknownCommandIsRefused() {
        assertFalse(last(1, "confirm").getBoolean("ok"))
        assertFalse(last(2, null).getBoolean("ok"))
    }

    @Test fun onlyTheShortListCanBeSet() {
        assertTrue(last(1, "set", "wakeWord", "true").getBoolean("ok"))
        assertFalse(last(2, "set", "apiKey", "x").getBoolean("ok"))
        assertEquals(1, port.set.size)
    }

    @Test fun openTakesOnlyItsTwoWays() {
        assertFalse(last(1, "open", "settings").getBoolean("ok"))
        assertEquals(null, port.opened)
        assertTrue(last(2, "open", "wake").getBoolean("ok"))
        assertEquals("wake", port.opened)
    }

    @Test fun aClipsNameCantBeAPath() {
        for (bad in listOf(null, "", "../results.jsonl", "say-0000000000000001.s16/../x", "say-1.s16", "say-0000000000000001.wav", "yes-0000000000000001.s16"))
            assertFalse("$bad", last(1, "play", bad).getBoolean("ok"))
        assertEquals(0, feed.pending())
    }

    @Test fun aMissingClipIsRefused() {
        assertFalse(last(1, "play", "say-0000000000000002.s16").getBoolean("ok"))
    }

    @Test fun aRiskyCardStopsEverythingButANo() {
        port.kinds = listOf("send_text")
        assertFalse(last(1, "play", say).getBoolean("ok"))
        assertEquals(0, feed.pending())
        assertTrue(last(2, "play", no).getBoolean("ok"))
        assertEquals(1, feed.pending())
    }

    @Test fun cardsThatCantBeReadStopEverythingButANo() {
        port.kinds = null
        assertFalse(last(1, "play", say).getBoolean("ok"))
        assertTrue(last(2, "play", no).getBoolean("ok"))
    }

    @Test fun whileRecordingNoClipPlays() {
        port.recording = true
        assertFalse(last(1, "play", no).getBoolean("ok"))
    }

    @Test fun aFailureIsARefusalAndStillOneAck() {
        port.boom = true
        assertFalse(last(1, "play", say).getBoolean("ok"))
        assertEquals(0, feed.pending())
    }

    @Test fun endAndResetDropTheQueueAndEndTheConversation() {
        assertTrue(last(1, "play", say).getBoolean("ok"))
        assertTrue(last(2, "end").getBoolean("ok"))
        assertEquals(0, feed.pending())
        assertEquals(1, port.ended)
        assertTrue(last(3, "play", say).getBoolean("ok"))
        assertTrue(last(4, "reset").getBoolean("ok"))
        assertEquals(0, feed.pending())
        assertEquals(2, port.ended)
        assertEquals(1, port.reset)
    }

    @Test fun importSaysHowManyFiles() = assertEquals(3, last(1, "import-models").getJSONObject("data").getInt("files"))

    @Test fun eachLineEndsAndARepliesNewlineStaysInsideIt() {
        val r = SpokenResults(file)
        r.line(JSONObject().put("reply", "two\nlines"))
        r.ack(5)
        assertEquals(2, file.readLines().size)
        assertTrue(file.readText().endsWith("\n"))
        assertEquals("two\nlines", JSONObject(file.readLines()[0]).getString("reply"))
    }

    @Test fun aClipOfAnOddLengthOrTooLongIsNotAClip() {
        val f = File(tmp.root, "c")
        f.writeBytes(byteArrayOf(1, 0, 0xFE.toByte(), 0xFF.toByte()))
        assertEquals(listOf<Short>(1, -2), SpokenProtocol.readClip(f)!!.toList())
        f.writeBytes(ByteArray(3))
        assertEquals(null, SpokenProtocol.readClip(f))
        f.writeBytes(ByteArray(SpokenProtocol.MAX_CLIP_BYTES + 2))
        assertEquals(null, SpokenProtocol.readClip(f))
    }
}
