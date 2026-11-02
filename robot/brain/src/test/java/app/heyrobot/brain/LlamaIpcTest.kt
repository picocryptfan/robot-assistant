// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What :brain reads back from llama.cpp's isolated process, which a hostile file may have taken over: nothing but the layout passes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LlamaIpcTest {
    private fun parcel(write: Parcel.() -> Unit): Parcel = Parcel.obtain().apply { write(); setDataPosition(0) }
    private fun reply(write: Parcel.() -> Unit) = LlamaIpc.Reply(parcel { writeInt(LlamaIpc.REPLY_OK); write() })
    private fun bad(block: () -> Unit) { assertThrows(LlamaIpc.BadReply::class.java) { block() } }

    @Test fun aReplyThatDoesNotStartWithItsPlainHeaderIsRefused() {
        bad { LlamaIpc.Reply(parcel { writeInt(1) }) }
        bad { LlamaIpc.Reply(parcel { writeInt(-1) }) }
        bad { LlamaIpc.Reply(parcel { }) }
        // What Parcel.writeException writes, with an exception's name after it: it is never unparcelled, only refused.
        bad { LlamaIpc.Reply(parcel { writeException(IllegalStateException("from the file")) }) }
    }

    @Test fun numbersAreReadOnlyWhileTheReplyHoldsThem() {
        val r = reply { writeInt(7); writeLong(1L shl 40) }
        assertEquals(7, r.int())
        assertEquals(1L shl 40, r.long())
        r.end()
        bad { r.int() }
        bad { r.long() }
        bad { r.string() }
        bad { reply { writeInt(1) }.long() }
    }

    @Test fun whatIsLeftOverBreaksTheReply() {
        val r = reply { writeInt(1); writeInt(2) }
        r.int()
        bad { r.end() }
    }

    @Test fun aByteArrayIsCappedAndMustBeThere() {
        val b = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(b, reply { writeByteArray(b) }.bytes(5))
        bad { reply { writeByteArray(b) }.bytes(4) }
        // No array (a length of -1), a negative length, and a length the reply doesn't hold.
        bad { reply { writeByteArray(null) }.bytes(5) }
        bad { reply { writeInt(-2) }.bytes(5) }
        bad { reply { writeInt(1000) }.bytes(5000) }
        bad { reply { writeInt(Int.MAX_VALUE) }.bytes(Int.MAX_VALUE) }
    }

    @Test fun exactlyTheCountOfLongs() {
        val s = longArrayOf(1, 2, 3)
        assertArrayEquals(s, reply { writeLongArray(s) }.longs(3))
        bad { reply { writeLongArray(s) }.longs(4) }
        bad { reply { writeLongArray(s) }.longs(2) }
        // Its count says 3, the reply holds one.
        bad { reply { writeInt(3); writeLong(1) }.longs(3) }
        bad { reply { writeInt(-1) }.longs(3) }
    }

    @Test fun arraysKeepTheirCountAndTheirTotal() {
        val items = listOf(byteArrayOf(1, 2), byteArrayOf(3))
        val out = reply { LlamaIpc.writeArrays(this, items) }.arrays(2, 3)!!
        assertEquals(2, out.size)
        assertArrayEquals(items[1], out[1])
        assertNull(reply { LlamaIpc.writeArrays(this, null) }.arrays(2, 3))
        bad { reply { LlamaIpc.writeArrays(this, items) }.arrays(1, 3) }
        // The second array passes on its own but not with the first: the cap is on the total.
        bad { reply { LlamaIpc.writeArrays(this, items) }.arrays(2, 2) }
        bad { reply { writeInt(-2) }.arrays(2, 3) }
        bad { reply { writeInt(2); writeByteArray(items[0]) }.arrays(2, 3) }
    }

    @Test fun oneArrayAtTheCapIsAllTheTotalAllows() {
        assertEquals(0, reply { LlamaIpc.writeArrays(this, emptyList()) }.arrays(2, 3)!!.size)
        val full = listOf(ByteArray(3), ByteArray(0))
        assertEquals(2, reply { LlamaIpc.writeArrays(this, full) }.arrays(2, 3)!!.size)
        bad { reply { LlamaIpc.writeArrays(this, listOf(ByteArray(3), ByteArray(1))) }.arrays(2, 3) }
    }

    @Test fun readArraysRefusesWhatIsOverItsCapsAsNone() {
        val items = listOf(byteArrayOf(1, 2), byteArrayOf(3))
        fun written(list: List<ByteArray>?) = parcel { LlamaIpc.writeArrays(this, list) }
        assertEquals(2, LlamaIpc.readArrays(written(items), 2, 3)!!.size)
        assertNull(LlamaIpc.readArrays(written(null), 2, 3))
        assertNull(LlamaIpc.readArrays(written(items), 1, 3))
        assertNull(LlamaIpc.readArrays(written(items), 2, 2))
    }
}
