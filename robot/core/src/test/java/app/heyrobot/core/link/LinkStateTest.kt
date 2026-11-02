// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.seal.FakeInboxWrap
import app.heyrobot.core.seal.FakeSecretWrap
import app.heyrobot.core.seal.KeyProblem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * robot-to-robot-plan.md §2.4 and §3.6 (job L2): :link's sealed records (files/link/state, robot_link_v1) and the waiting
 * inbox (files/link/inbox, robot_inbox_v1). The key made only while nothing is sealed, a missing key with records GONE,
 * a damaged record kept and DAMAGED, a newer one NEWER, deletes that destroy the wrapped key first, writes cut short, and
 * the inbox: sealed while locked, opened only unlocked, capped, nothing of the sender on storage in the clear.
 */
class LinkStateTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val pairing = "0123456789abcdef0123456789abcdef"
    private val session = RecordKey(LinkPurpose.SESSION, pairing)
    private val identity = RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO)

    private fun problem(block: () -> Unit): LinkStateProblem? = try {
        block()
        null
    } catch (x: LinkStateException) {
        x.problem
    }

    @Test fun recordsRoundTripAndTheirNames() {
        val dir = File(tmp.root, "state")
        val keys = FakeSecretWrap(present = false)
        val r = LinkRecords(dir, keys)
        assertFalse(r.anything())
        assertNull(r.read(session))
        r.write(identity, byteArrayOf(1, 2, 3))
        r.write(session, ByteArray(5000) { it.toByte() })
        r.write(RecordKey(LinkPurpose.KYBER_PREKEY, RecordKey.number(7)), byteArrayOf(9))
        assertEquals(1, keys.created)
        assertTrue(r.anything())
        assertArrayEquals(byteArrayOf(1, 2, 3), r.read(identity))
        assertEquals(setOf("1-00000000000000000000000000000000.rl", "5-$pairing.rl", "4-00000000000000000000000000000007.rl"),
            dir.list()!!.toSet())
        val all = r.readAll()
        assertEquals(3, all.size)
        assertArrayEquals(ByteArray(5000) { it.toByte() }, all[session])
        // A record written again replaces the old one, with a fresh key.
        r.write(session, byteArrayOf(4))
        assertArrayEquals(byteArrayOf(4), r.read(session))
        assertEquals(keys.keys.size, keys.keys.toSet().size)
        // Names this version doesn't know are left alone, never read as records.
        File(dir, "notes.txt").writeText("x")
        // (99: L3 gave 7 to 12 to the carriers' records, so 9, used here in L2, is a known purpose now.)
        File(dir, "99-$pairing.rl").writeText("x")
        File(dir, "05-$pairing.rl").writeText("x")
        assertEquals(3, r.readAll().size)
        assertTrue(File(dir, "notes.txt").isFile)
        // The bytes on storage are sealed: ROBOTLNK format 1, nothing of the plaintext.
        val f = File(dir, "5-$pairing.rl").readBytes()
        assertArrayEquals("ROBOTLNK".toByteArray(), f.copyOf(8))
        assertEquals(LinkPurpose.SESSION.code, f[10].toInt())
    }

    /** §2.4: robot_link_v1 is made only while the folder holds nothing at all; a missing key with records is GONE. */
    @Test fun theKeyIsMadeOnlyWhileNothingIsSealed() {
        val dir = File(tmp.root, "state")
        dir.mkdirs()
        File(dir, ".1-${RecordKey.ZERO}.rl.tmp").writeText("cut short")
        val keys = FakeSecretWrap(present = false)
        val r = LinkRecords(dir, keys)
        assertEquals(LinkStateProblem.GONE, problem { r.write(identity, byteArrayOf(1)) })
        assertEquals(0, keys.created)
        // Records sealed under a key that is gone now: GONE when read, and they stay.
        val other = File(tmp.root, "other")
        LinkRecords(other, FakeSecretWrap()).write(session, byteArrayOf(1))
        val lost = LinkRecords(other, FakeSecretWrap(present = false))
        assertEquals(LinkStateProblem.GONE, problem { lost.readAll() })
        assertEquals(LinkStateProblem.GONE, problem { lost.write(identity, byteArrayOf(1)) })
        assertTrue(File(other, session.fileName).isFile)
    }

    @Test fun lockedDamagedNewerAndTooBig() {
        val dir = File(tmp.root, "state")
        val keys = FakeSecretWrap()
        val r = LinkRecords(dir, keys)
        r.write(session, byteArrayOf(1, 2, 3))
        keys.refuse = KeyProblem.LOCKED
        assertEquals(LinkStateProblem.LOCKED, problem { r.readAll() })
        assertEquals(LinkStateProblem.LOCKED, problem { r.write(identity, byteArrayOf(1)) })
        keys.refuse = null
        keys.boom = true
        assertEquals(LinkStateProblem.LOCKED, problem { r.read(session) })
        keys.boom = false
        // Another key's record, a changed byte, a record copied under another name: DAMAGED, and kept.
        assertEquals(LinkStateProblem.DAMAGED, problem { LinkRecords(dir, FakeSecretWrap(seed = 9)).read(session) })
        val f = r.file(session)
        val good = f.readBytes()
        f.writeBytes(good.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() })
        assertEquals(LinkStateProblem.DAMAGED, problem { r.readAll() })
        assertTrue(f.isFile)
        f.writeBytes(good)
        val moved = RecordKey(LinkPurpose.SESSION, "fedcba9876543210fedcba9876543210")
        r.file(moved).writeBytes(good)
        assertEquals(LinkStateProblem.DAMAGED, problem { r.read(moved) })
        r.file(moved).delete()
        // A newer format: NEWER, never read or written over by this Robot.
        f.writeBytes(good.copyOf().also { it[9] = 3 })
        assertEquals(LinkStateProblem.NEWER, problem { r.read(session) })
        f.writeBytes(good)
        // Over the purpose's cap: nothing written.
        assertEquals(LinkStateProblem.TOO_BIG, problem { r.write(RecordKey(LinkPurpose.PEER, pairing), ByteArray(1025)) })
        assertFalse(r.file(RecordKey(LinkPurpose.PEER, pairing)).exists())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    /** F5: what [delete] leaves on storage when a cut comes after its overwrite and before its unlink: those very bytes. */
    private fun cutBeforeUnlink(f: File, delete: () -> Unit) {
        val left = RandomAccessFile(f, "r").use { raf ->
            delete()
            ByteArray(raf.length().toInt()).also { raf.seek(0); raf.readFully(it) }
        }
        f.writeBytes(left)
    }

    /** F5: a cut at each step of a delete leaves Robot to Robot able to open, with only that record gone. */
    @Test fun aCutAtEachDeleteStepStillOpens() {
        val dir = File(tmp.root, "state")
        val r = LinkRecords(dir, FakeSecretWrap())
        val other = RecordKey(LinkPurpose.PEER, pairing)
        r.write(session, ByteArray(100) { 7 })
        r.write(other, ByteArray(10) { 8 })
        val f = r.file(session)
        // Before the overwrite: nothing has happened yet, and the record is still whole.
        assertEquals(setOf(session, other), r.readAll().keys)
        // After the overwrite, before the unlink: the delete finishes at the next start.
        cutBeforeUnlink(f) { r.delete(session) }
        assertTrue(f.isFile)
        assertEquals(setOf(other), r.readAll().keys)
        assertFalse(f.exists())
        // After the unlink, before the folder's sync: no record, no change.
        assertEquals(setOf(other), r.readAll().keys)
        // A read of that one record says the same.
        r.write(session, ByteArray(100) { 7 })
        cutBeforeUnlink(f) { r.delete(session) }
        assertEquals(null, r.read(session))
        assertFalse(f.exists())
        // Every record cut: nothing left to read, and no error.
        cutBeforeUnlink(r.file(other)) { r.delete(other) }
        assertEquals(emptyMap<RecordKey, ByteArray>(), r.readAll())
    }

    /** F5: the same in a waiting inbox, with the longer wrapped key of its format: open is a null, and the item is gone. */
    @Test fun aCutInboxDeleteFinishes() {
        val dir = File(tmp.root, "inbox")
        val inbox = LinkInbox(dir, FakeInboxWrap())
        val id = inbox.put(pairing, 1_761_080_000, byteArrayOf(1))
        val keep = inbox.put(pairing, 1_761_080_001, byteArrayOf(2))
        val f = File(dir, "$id${LinkRecords.EXT}")
        cutBeforeUnlink(f) { inbox.delete(id) }
        assertTrue(f.isFile)
        assertEquals(null, inbox.open(id))
        assertFalse(f.exists())
        assertTrue(inbox.open(keep) != null)
    }

    /** F5: real damage is never taken for a delete: a record with any of its wrapped key left stays DAMAGED and stays there. */
    @Test fun damageIsNotADelete() {
        val r = LinkRecords(File(tmp.root, "state"), FakeSecretWrap())
        r.write(session, ByteArray(100) { 7 })
        val f = r.file(session)
        val good = f.readBytes()
        // The size alone zeroed: the key is still there.
        f.writeBytes(good.copyOf().also { it[28] = 0; it[29] = 0 })
        assertEquals(LinkStateProblem.DAMAGED, problem { r.readAll() })
        assertTrue(f.isFile)
        // Another format of file with the same zeros: not ours.
        f.writeBytes(ByteArray(good.size))
        assertEquals(LinkStateProblem.DAMAGED, problem { r.readAll() })
        assertTrue(f.isFile)
    }

    /** A delete overwrites the wrapped key first: a copy of the file taken just before can't be opened after. */
    @Test fun deletingDestroysTheWrappedKeyFirst() {
        val dir = File(tmp.root, "state")
        val r = LinkRecords(dir, FakeSecretWrap())
        r.write(session, ByteArray(100) { 7 })
        val f = r.file(session)
        // Watch the bytes the delete leaves behind by keeping the file open (its inode stays readable).
        RandomAccessFile(f, "r").use { raf ->
            r.delete(session)
            assertFalse(f.exists())
            val left = ByteArray(96).also { raf.seek(0); raf.readFully(it) }
            assertTrue(left.copyOfRange(28, 90).all { it.toInt() == 0 })
        }
        // A write cut short (its temporary file) is cleared at the next start.
        File(dir, ".5-$pairing.rl.tmp").writeBytes(ByteArray(200) { 1 })
        r.readAll()
        assertFalse(File(dir, ".5-$pairing.rl.tmp").exists())
        // Start over: everything gone, the folder too.
        r.write(session, byteArrayOf(1))
        r.deleteAll()
        assertFalse(dir.exists())
    }

    @Test fun recordKeysAreStrict() {
        assertEquals(RecordKey(LinkPurpose.SESSION, pairing), RecordKey.of("5-$pairing.rl"))
        for (bad in listOf("5-$pairing", "5-${pairing.uppercase()}.rl", "6-$pairing.rl", "0-$pairing.rl", "-$pairing.rl", "+5-$pairing.rl",
            "5-${pairing}0.rl", "5$pairing.rl", ".5-$pairing.rl.tmp")) assertNull(bad, RecordKey.of(bad))
        assertTrue(runCatching { RecordKey(LinkPurpose.WAITING, pairing) }.isFailure)
        assertEquals("00000000000000000000000000ffffff", RecordKey.number(0xFFFFFF))
        assertEquals("RecordKey(SESSION)", RecordKey(LinkPurpose.SESSION, pairing).toString())
    }

    // The waiting inbox ----------------------------------------------------------------------------------------------------

    /** §3.6 and §4.4: a request that must wait is sealed while locked; opening it needs the unlock. */
    @Test fun theInboxSealsWhileLockedAndOpensOnlyUnlocked() {
        val dir = File(tmp.root, "inbox")
        val w = FakeInboxWrap().apply { locked = true }
        val inbox = LinkInbox(dir, w)
        val request = "{\"v\":1}".toByteArray() + ByteArray(500) { ' '.code.toByte() }
        val id = inbox.put(pairing, 1_761_080_000, request)
        val second = inbox.put("fedcba9876543210fedcba9876543210", 1_761_080_001, byteArrayOf(1))
        assertEquals(listOf(id, second).toSet(), inbox.ids().toSet())
        assertEquals(LinkStateProblem.LOCKED, problem { inbox.open(id) })
        assertEquals(0, w.unwraps)
        w.locked = false
        val got = inbox.open(id)!!
        assertEquals(pairing, got.pairing)
        assertEquals(1_761_080_000, got.receivedAt)
        assertArrayEquals(request, got.request)
        // Nothing of the sender or the request on storage in the clear: the pairing id is inside the sealed bytes.
        val f = File(dir, "$id.rl").readBytes()
        assertArrayEquals("ROBOTLNK".toByteArray(), f.copyOf(8))
        assertEquals(2, f[9].toInt())
        assertEquals(LinkPurpose.WAITING.code, f[10].toInt())
        assertFalse(String(f, Charsets.ISO_8859_1).contains(pairing))
        assertFalse(LinkBytes.hex(f).contains(pairing))
        assertFalse(String(f, Charsets.ISO_8859_1).contains("{\"v\":1}"))
        inbox.delete(id)
        assertNull(inbox.open(id))
        assertEquals(listOf(second), inbox.ids())
        assertEquals(LinkStateProblem.GONE, problem { LinkInbox(dir, FakeInboxWrap().apply { gone = true }).open(second) })
        assertEquals(LinkStateProblem.DAMAGED, problem { LinkInbox(dir, FakeInboxWrap(FakeInboxWrap.OTHER)).open(second) })
        assertNull(inbox.open("not an id"))
        inbox.deleteAll()
        assertFalse(dir.exists())
    }

    @Test fun theInboxIsCapped() {
        val dir = File(tmp.root, "inbox")
        val inbox = LinkInbox(dir, FakeInboxWrap())
        assertEquals(LinkStateProblem.TOO_BIG, problem { inbox.put(pairing, 1, ByteArray(LinkInbox.MAX_REQUEST + 1)) })
        assertEquals(32_768, LinkInbox.MAX_REQUEST)
        assertTrue(inbox.ids().isEmpty())
        inbox.put(pairing, 1, ByteArray(LinkInbox.MAX_REQUEST))
        // Fill to the cap with stand-ins (sealing 500 items here would only make the test slow): the next is refused.
        dir.mkdirs()
        repeat(LinkInbox.MAX - 1) { File(dir, "${LinkIds.newId()}.rl").writeBytes(byteArrayOf(0)) }
        assertEquals(LinkStateProblem.FULL, problem { inbox.put(pairing, 1, byteArrayOf(1)) })
        assertEquals(LinkInbox.MAX, inbox.count())
        // A wrong pairing id or time is a programming error, not something to store.
        assertTrue(runCatching { inbox.put("0".repeat(32), 1, byteArrayOf(1)) }.isFailure)
        assertTrue(runCatching { inbox.put(pairing, -1, byteArrayOf(1)) }.isFailure)
    }

    @Test fun aTamperedOrMovedInboxItemIsRefused() {
        val dir = File(tmp.root, "inbox")
        val inbox = LinkInbox(dir, FakeInboxWrap())
        val a = inbox.put(pairing, 5, byteArrayOf(1, 2, 3))
        val b = inbox.put(pairing, 6, byteArrayOf(4, 5, 6))
        val fa = File(dir, "$a.rl")
        val fb = File(dir, "$b.rl")
        val bBytes = fb.readBytes()
        // B's bytes under A's name: its id isn't A's.
        fa.writeBytes(bBytes)
        assertEquals(LinkStateProblem.DAMAGED, problem { inbox.open(a) })
        // A changed byte anywhere.
        for (at in listOf(0, 10, 12, 100, 420, bBytes.size - 1)) {
            fb.writeBytes(bBytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() })
            val p = problem { inbox.open(b) }
            assertTrue("byte $at: $p", p == LinkStateProblem.DAMAGED || p == LinkStateProblem.NEWER)
        }
    }
}
